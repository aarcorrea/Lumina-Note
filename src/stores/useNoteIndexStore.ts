import { create } from "zustand";
import { readFile, walkPaths } from "@/lib/host";

// Extract [[wikilinks]] from content
export function extractWikiLinks(content: string): string[] {
  const regex = /\[\[([^\]|]+)(?:\|[^\]]+)?\]\]/g;
  const links: string[] = [];
  let match;
  while ((match = regex.exec(content)) !== null) {
    links.push(match[1].trim());
  }
  return [...new Set(links)];
}

// Extract #tags from content
export function extractTags(content: string): string[] {
  // Match #tag but not inside code blocks or URLs
  const regex = /(?:^|\s)#([a-zA-Z一-龥][a-zA-Z0-9一-龥_-]*)/g;
  const tags: string[] = [];
  let match;
  while ((match = regex.exec(content)) !== null) {
    tags.push(match[1].toLowerCase());
  }
  return [...new Set(tags)];
}

// Note metadata for indexing
export interface NoteIndex {
  path: string;
  name: string;
  outgoingLinks: string[]; // [[links]] this note contains
  tags: string[];
  lastModified: number;
}

// Backlink entry
export interface Backlink {
  path: string;
  name: string;
  context: string; // Line containing the link
  line: number;
}

// Tag with count
export interface TagInfo {
  tag: string;
  count: number;
  files: string[];
}

interface NoteIndexState {
  noteIndex: Map<string, NoteIndex>;
  backlinksCache: Map<string, Backlink[]>;
  allTags: TagInfo[];
  isIndexing: boolean;
  lastIndexTime: number;
  /** Number of notes the last build attempted to index. */
  totalNotes: number;
  /** Indexed-so-far counter; updates during a build. */
  indexedCount: number;
  /** True when the walker hit the path cap and stopped early. */
  truncated: boolean;

  buildIndex: (vaultPath: string) => Promise<void>;
  cancelIndex: () => void;
  getBacklinks: (noteName: string) => Backlink[];
  getTagFiles: (tag: string) => string[];
  searchContent: (query: string, vaultPath: string) => Promise<SearchResult[]>;
}

export interface SearchResult {
  path: string;
  name: string;
  matches: SearchMatch[];
  score: number;
}

export interface SearchMatch {
  line: number;
  content: string;
  matchStart: number;
  matchEnd: number;
}

// Hard cap on individual note size — anything bigger is almost certainly
// not a note (a paste-buffer dump, a binary mistakenly named .md, etc.)
// and would balloon the index for no real query value.
const MAX_NOTE_BYTES = 2_000_000;

// Cap on total notes the in-memory indexer will read. Above this we stop
// and surface `truncated`. 50k is well past any reasonable vault.
const MAX_INDEXED_NOTES = 50_000;

// Concurrent readFile() calls during build. Each is one IPC round-trip
// to the main process; ~16 saturates a fast SSD without flooding the
// IPC channel.
const READ_CONCURRENCY = 16;

// After this many files, yield to the event loop so the renderer can
// service input/animation frames during a build.
const YIELD_EVERY = 200;

// Track the latest in-flight build so we can cancel on vault switch
// without exposing AbortController to callers.
let currentBuild: { abort: AbortController; vault: string } | null = null;

function nameFromPath(p: string): string {
  const slash = Math.max(p.lastIndexOf("/"), p.lastIndexOf("\\"));
  const base = slash >= 0 ? p.slice(slash + 1) : p;
  return base.endsWith(".md") ? base.slice(0, -3) : base;
}

function nextIdle(): Promise<void> {
  return new Promise((resolve) => {
    const w = window as unknown as {
      requestIdleCallback?: (cb: () => void) => void;
    };
    if (typeof w.requestIdleCallback === "function") {
      w.requestIdleCallback(() => resolve());
    } else {
      setTimeout(resolve, 0);
    }
  });
}

function nextMicroBreak(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

export const useNoteIndexStore = create<NoteIndexState>((set, get) => ({
  noteIndex: new Map(),
  backlinksCache: new Map(),
  allTags: [],
  isIndexing: false,
  lastIndexTime: 0,
  totalNotes: 0,
  indexedCount: 0,
  truncated: false,

  cancelIndex: () => {
    currentBuild?.abort.abort();
    currentBuild = null;
  },

  buildIndex: async (vaultPath: string) => {
    // Cancel any prior build for a different vault, or restart cleanly.
    if (currentBuild) {
      currentBuild.abort.abort();
      currentBuild = null;
    }
    const abort = new AbortController();
    currentBuild = { abort, vault: vaultPath };

    set({
      isIndexing: true,
      totalNotes: 0,
      indexedCount: 0,
      truncated: false,
    });

    // Step 1: defer the build off the critical path. The vault has just
    // opened; let the sidebar paint first.
    await nextIdle();
    if (abort.signal.aborted) return;

    // Step 2: enumerate .md files via the server-side walker. This
    // respects .gitignore and the default deny set, returns a flat list,
    // and skips oversized files at the source — the renderer never
    // touches them.
    let walk;
    try {
      walk = await walkPaths(vaultPath, {
        extensions: [".md"],
        maxPaths: MAX_INDEXED_NOTES,
        maxFileSizeBytes: MAX_NOTE_BYTES,
      });
    } catch (error) {
      console.error("[NoteIndex] walkPaths failed:", error);
      set({ isIndexing: false });
      currentBuild = null;
      return;
    }
    if (abort.signal.aborted) return;

    const rawPaths = walk?.paths;
    const allFiles = Array.isArray(rawPaths)
      ? rawPaths.map((p) => ({ path: p, name: nameFromPath(p) }))
      : [];
    set({ totalNotes: allFiles.length, truncated: Boolean(walk?.truncated) });

    const noteIndex = new Map<string, NoteIndex>();
    const backlinksMap = new Map<string, Backlink[]>();
    const tagsMap = new Map<string, { count: number; files: string[] }>();

    let processed = 0;

    // Step 3: read + parse with bounded concurrency. We don't use a full
    // p-limit dep — a hand-rolled fixed-size worker pool over a queue is
    // ~20 lines and avoids adding a runtime dep. The pool yields to the
    // event loop every YIELD_EVERY files so input/animation stays
    // responsive even mid-build.
    const queue = allFiles.slice();
    const workers: Promise<void>[] = [];

    const indexOne = async (file: { path: string; name: string }) => {
      if (abort.signal.aborted) return;
      try {
        const content = await readFile(file.path);
        const outgoingLinks = extractWikiLinks(content);
        const tags = extractTags(content);

        noteIndex.set(file.path, {
          path: file.path,
          name: file.name,
          outgoingLinks,
          tags,
          lastModified: Date.now(),
        });

        // Build backlinks: for each outgoing link, record this file as
        // a referrer of the linked note name.
        const lines = content.split("\n");
        for (const linkName of outgoingLinks) {
          let contextLine = "";
          let lineNum = 0;
          const target = `[[${linkName}`;
          const targetLower = target.toLowerCase();
          for (let i = 0; i < lines.length; i++) {
            const line = lines[i];
            if (
              line.includes(target) ||
              line.toLowerCase().includes(targetLower)
            ) {
              contextLine = line.trim();
              lineNum = i + 1;
              break;
            }
          }
          const backlink: Backlink = {
            path: file.path,
            name: file.name,
            context: contextLine,
            line: lineNum,
          };
          const key = linkName.toLowerCase();
          const list = backlinksMap.get(key);
          if (list) list.push(backlink);
          else backlinksMap.set(key, [backlink]);
        }

        for (const tag of tags) {
          const info = tagsMap.get(tag);
          if (info) {
            info.count++;
            info.files.push(file.path);
          } else {
            tagsMap.set(tag, { count: 1, files: [file.path] });
          }
        }
      } catch (error) {
        // Silently skip unreadable files — they shouldn't block the
        // overall index and the user sees them in the sidebar anyway.
        if (import.meta.env.DEV) {
          console.warn(`[NoteIndex] skip ${file.path}:`, error);
        }
      }

      processed++;
      if (processed % YIELD_EVERY === 0) {
        set({ indexedCount: processed });
        await nextMicroBreak();
      }
    };

    const runWorker = async () => {
      while (queue.length > 0 && !abort.signal.aborted) {
        const file = queue.shift();
        if (!file) break;
        await indexOne(file);
      }
    };

    for (let i = 0; i < READ_CONCURRENCY; i++) {
      workers.push(runWorker());
    }
    await Promise.all(workers);

    if (abort.signal.aborted) return;

    const allTags: TagInfo[] = Array.from(tagsMap.entries())
      .map(([tag, info]) => ({ tag, count: info.count, files: info.files }))
      .sort((a, b) => b.count - a.count);

    set({
      noteIndex,
      backlinksCache: backlinksMap,
      allTags,
      isIndexing: false,
      lastIndexTime: Date.now(),
      indexedCount: processed,
    });

    if (currentBuild?.abort === abort) currentBuild = null;

    if (import.meta.env.DEV) {
      console.log(
        `[Index] Built index for ${noteIndex.size} notes, ${allTags.length} tags${
          walk.truncated ? ` (truncated at ${MAX_INDEXED_NOTES})` : ""
        }`,
      );
    }
  },

  getBacklinks: (noteName: string) => {
    const { backlinksCache } = get();
    return backlinksCache.get(noteName.toLowerCase()) || [];
  },

  getTagFiles: (tag: string) => {
    const { allTags } = get();
    const tagInfo = allTags.find((t) => t.tag === tag.toLowerCase());
    return tagInfo?.files || [];
  },

  searchContent: async (query: string, vaultPath: string) => {
    if (!query.trim()) return [];

    const results: SearchResult[] = [];
    const pattern = new RegExp(
      query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
      "gi",
    );

    let walk;
    try {
      walk = await walkPaths(vaultPath, {
        extensions: [".md"],
        maxPaths: MAX_INDEXED_NOTES,
        maxFileSizeBytes: MAX_NOTE_BYTES,
      });
    } catch {
      return [];
    }

    const rawPaths = walk?.paths;
    const allFiles = Array.isArray(rawPaths)
      ? rawPaths.map((p) => ({ path: p, name: nameFromPath(p) }))
      : [];
    const queue = allFiles.slice();

    const searchOne = async (file: { path: string; name: string }) => {
      try {
        const content = await readFile(file.path);
        const lines = content.split("\n");
        const matches: SearchMatch[] = [];

        lines.forEach((line, lineIndex) => {
          let match;
          pattern.lastIndex = 0;
          while ((match = pattern.exec(line)) !== null) {
            matches.push({
              line: lineIndex + 1,
              content: line.trim(),
              matchStart: match.index,
              matchEnd: match.index + match[0].length,
            });
            if (match[0].length === 0) break;
          }
        });

        if (matches.length > 0) {
          const titleMatch = file.name
            .toLowerCase()
            .includes(query.toLowerCase());
          const score = (titleMatch ? 100 : 0) + matches.length;
          results.push({
            path: file.path,
            name: file.name,
            matches,
            score,
          });
        }
      } catch {
        // Skip unreadable files
      }
    };

    const runWorker = async () => {
      while (queue.length > 0) {
        const file = queue.shift();
        if (!file) break;
        await searchOne(file);
      }
    };

    const workers: Promise<void>[] = [];
    for (let i = 0; i < READ_CONCURRENCY; i++) workers.push(runWorker());
    await Promise.all(workers);

    return results.sort((a, b) => b.score - a.score);
  },
}));
