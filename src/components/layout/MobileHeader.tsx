import { useCallback } from "react";
import {
  Folder,
  Search,
  Bot,
  Plus,
  Eye,
  Edit3,
  AlignLeft,
  FileText,
  Network,
} from "lucide-react";
import type { Tab } from "@/stores/useFileStore";
import { useUIStore } from "@/stores/useUIStore";
import { useLocaleStore } from "@/stores/useLocaleStore";
import { useOpencodeAgent } from "@/stores/useOpencodeAgent";

interface MobileHeaderProps {
  activeTab: Tab | null;
  currentFile: string | null;
  onToggleSidebar: () => void;
  onToggleOutline: () => void;
  onOpenSearch?: () => void;
}

export function MobileHeader({
  activeTab,
  currentFile,
  onToggleSidebar,
  onToggleOutline,
  onOpenSearch,
}: MobileHeaderProps) {
  const { t } = useLocaleStore();
  const leftSidebarOpen = useUIStore((s) => s.leftSidebarOpen);
  const rightSidebarOpen = useUIStore((s) => s.rightSidebarOpen);
  const editorMode = useUIStore((s) => s.editorMode);
  const setEditorMode = useUIStore((s) => s.setEditorMode);
  const clearChat = useOpencodeAgent((s) => s.clearChat);

  const isAIChat = activeTab?.type === "ai-chat";
  const isGraph = activeTab?.type === "graph" || activeTab?.type === "isolated-graph";

  const getTitle = () => {
    if (isAIChat) return t.common?.aiChatTab || "AI Assistant";
    if (isGraph) return t.graph?.title || "Graph View";
    if (currentFile) {
      const parts = currentFile.split(/[/\\]/);
      const name = parts[parts.length - 1] || currentFile;
      return name.replace(/\.md$/i, "");
    }
    return activeTab?.name || "Metis Notes";
  };

  const handleToggleMode = useCallback(() => {
    setEditorMode(editorMode === "live" ? "preview" : "live");
  }, [editorMode, setEditorMode]);

  return (
    <header className="flex h-11 w-full items-center justify-between border-b border-border/60 bg-popover/90 dark:bg-background/90 px-2 backdrop-blur-md z-30 flex-shrink-0 select-none">
      {/* Left: Sidebar Toggle */}
      <div className="flex items-center gap-1 min-w-[70px]">
        <button
          type="button"
          onClick={onToggleSidebar}
          className={`flex h-8 w-8 items-center justify-center rounded-lg transition-colors ${
            leftSidebarOpen
              ? "bg-primary/10 text-primary"
              : "text-muted-foreground hover:bg-accent hover:text-foreground"
          }`}
          aria-label={t.sidebar?.files || "Files"}
        >
          <Folder size={18} />
        </button>
        {onOpenSearch && (
          <button
            type="button"
            onClick={onOpenSearch}
            className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
            aria-label={t.overview?.globalSearch || "Search"}
          >
            <Search size={18} />
          </button>
        )}
      </div>

      {/* Center: Title */}
      <div className="flex flex-1 items-center justify-center min-w-0 px-2">
        <div className="flex items-center gap-1.5 max-w-full">
          {isAIChat ? (
            <Bot size={16} className="text-primary flex-shrink-0" />
          ) : isGraph ? (
            <Network size={16} className="text-indigo-500 flex-shrink-0" />
          ) : (
            <FileText size={16} className="text-muted-foreground flex-shrink-0" />
          )}
          <span className="text-sm font-semibold truncate text-foreground text-center">
            {getTitle()}
          </span>
          {activeTab?.isDirty && (
            <span className="h-1.5 w-1.5 rounded-full bg-primary flex-shrink-0" />
          )}
        </div>
      </div>

      {/* Right: Context Actions */}
      <div className="flex items-center justify-end gap-1 min-w-[70px]">
        {isAIChat ? (
          <button
            type="button"
            onClick={() => void clearChat()}
            className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
            title="New Chat"
            aria-label="New Chat"
          >
            <Plus size={18} />
          </button>
        ) : (
          <>
            <button
              type="button"
              onClick={handleToggleMode}
              className={`flex h-8 w-8 items-center justify-center rounded-lg transition-colors ${
                editorMode === "preview"
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-accent hover:text-foreground"
              }`}
              title={editorMode === "preview" ? "Edit Mode" : "Reading Mode"}
              aria-label="Toggle Reading Mode"
            >
              {editorMode === "preview" ? <Edit3 size={17} /> : <Eye size={17} />}
            </button>

            <button
              type="button"
              onClick={onToggleOutline}
              className={`flex h-8 w-8 items-center justify-center rounded-lg transition-colors ${
                rightSidebarOpen
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-accent hover:text-foreground"
              }`}
              title="Outline"
              aria-label="Outline"
            >
              <AlignLeft size={18} />
            </button>
          </>
        )}
      </div>
    </header>
  );
}
