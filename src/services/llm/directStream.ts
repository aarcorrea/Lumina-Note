import type { AIConfig } from "@/services/ai/ai";
import { PROVIDER_MODELS, type LLMProviderType } from "./providers/models";

export interface DirectStreamChunk {
  type: "text" | "reasoning" | "error";
  text?: string;
  error?: string;
}

/**
 * Stream LLM completion directly via standard fetch / SSE.
 * Used on mobile and standalone environments where the Electron opencode Node.js server is not available.
 */
export async function* streamDirectLLM(
  messages: Array<{ role: string; content: string }>,
  config: AIConfig,
  signal?: AbortSignal
): AsyncGenerator<DirectStreamChunk, void, unknown> {
  const provider = config.provider || "openai";
  const apiKey = config.apiKey?.trim() || "";

  if (!apiKey && provider !== "ollama") {
    yield {
      type: "text",
      text: "⚠️ No API key configured.\n\nPlease open **Settings → AI Settings** to configure your API key to chat with AI.",
    };
    return;
  }

  const modelId =
    config.model === "custom"
      ? config.customModelId?.trim() || "gpt-4o-mini"
      : config.model || "gpt-4o-mini";

  try {
    if (provider === "anthropic") {
      yield* streamAnthropic(messages, modelId, config, signal);
    } else if (provider === "google") {
      yield* streamGoogle(messages, modelId, config, signal);
    } else {
      yield* streamOpenAICompatible(messages, modelId, config, signal);
    }
  } catch (err: unknown) {
    if (signal?.aborted) return;
    const msg = err instanceof Error ? err.message : String(err);
    yield { type: "error", error: msg };
  }
}

async function* streamOpenAICompatible(
  messages: Array<{ role: string; content: string }>,
  modelId: string,
  config: AIConfig,
  signal?: AbortSignal
): AsyncGenerator<DirectStreamChunk, void, unknown> {
  let baseUrl = config.baseUrl?.trim();
  if (!baseUrl) {
    const meta = PROVIDER_MODELS[config.provider as LLMProviderType];
    baseUrl = meta?.defaultBaseUrl || "https://api.openai.com/v1";
  }

  const endpoint = baseUrl.endsWith("/chat/completions")
    ? baseUrl
    : `${baseUrl.replace(/\/+$/, "")}/chat/completions`;

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };

  if (config.apiKey) {
    headers["Authorization"] = `Bearer ${config.apiKey.trim()}`;
  }

  if (config.provider === "openrouter") {
    headers["HTTP-Referer"] = "https://luminanote.app";
    headers["X-Title"] = "Lumina Note";
  }

  const res = await fetch(endpoint, {
    method: "POST",
    headers,
    body: JSON.stringify({
      model: modelId,
      messages: messages.map((m) => ({
        role: m.role === "tool" ? "assistant" : m.role,
        content: m.content,
      })),
      stream: true,
      temperature: config.temperature ?? 0.7,
    }),
    signal,
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${errText.slice(0, 300)}`);
  }

  if (!res.body) {
    throw new Error("No response body received from LLM endpoint");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const lines = buffer.split("\n");
    buffer = lines.pop() || "";

    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line || !line.startsWith("data:")) continue;
      const dataStr = line.slice(5).trim();
      if (dataStr === "[DONE]") return;

      try {
        const json = JSON.parse(dataStr);
        const delta = json.choices?.[0]?.delta;
        if (delta?.reasoning_content) {
          yield { type: "reasoning", text: delta.reasoning_content };
        }
        if (delta?.content) {
          yield { type: "text", text: delta.content };
        }
      } catch {
        // partial json, ignore
      }
    }
  }
}

async function* streamAnthropic(
  messages: Array<{ role: string; content: string }>,
  modelId: string,
  config: AIConfig,
  signal?: AbortSignal
): AsyncGenerator<DirectStreamChunk, void, unknown> {
  const baseUrl = config.baseUrl?.trim() || "https://api.anthropic.com";
  const endpoint = `${baseUrl.replace(/\/+$/, "")}/v1/messages`;

  const systemMessages = messages.filter((m) => m.role === "system");
  const chatMessages = messages.filter((m) => m.role !== "system");

  const res = await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": config.apiKey?.trim() || "",
      "anthropic-version": "2023-06-01",
      "anthropic-dangerous-direct-browser-access": "true",
    },
    body: JSON.stringify({
      model: modelId,
      max_tokens: 4096,
      messages: chatMessages.map((m) => ({
        role: m.role === "assistant" ? "assistant" : "user",
        content: m.content,
      })),
      system:
        systemMessages.length > 0
          ? systemMessages.map((m) => m.content).join("\n\n")
          : undefined,
      stream: true,
      temperature: config.temperature ?? 0.7,
    }),
    signal,
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => "");
    throw new Error(`Anthropic error ${res.status}: ${errText.slice(0, 300)}`);
  }

  if (!res.body) {
    throw new Error("No response body received from Anthropic");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const lines = buffer.split("\n");
    buffer = lines.pop() || "";

    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line || !line.startsWith("data:")) continue;
      const dataStr = line.slice(5).trim();

      try {
        const json = JSON.parse(dataStr);
        if (json.type === "content_block_delta" && json.delta?.text) {
          yield { type: "text", text: json.delta.text };
        }
      } catch {
        // ignore
      }
    }
  }
}

async function* streamGoogle(
  messages: Array<{ role: string; content: string }>,
  modelId: string,
  config: AIConfig,
  signal?: AbortSignal
): AsyncGenerator<DirectStreamChunk, void, unknown> {
  const baseUrl =
    config.baseUrl?.trim() || "https://generativelanguage.googleapis.com";
  const apiKey = encodeURIComponent(config.apiKey?.trim() || "");
  const endpoint = `${baseUrl.replace(/\/+$/, "")}/v1beta/models/${modelId}:streamGenerateContent?key=${apiKey}&alt=sse`;

  const res = await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      contents: messages.map((m) => ({
        role: m.role === "assistant" ? "model" : "user",
        parts: [{ text: m.content }],
      })),
    }),
    signal,
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => "");
    throw new Error(`Gemini error ${res.status}: ${errText.slice(0, 300)}`);
  }

  if (!res.body) {
    throw new Error("No response body received from Gemini");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const lines = buffer.split("\n");
    buffer = lines.pop() || "";

    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line || !line.startsWith("data:")) continue;
      const dataStr = line.slice(5).trim();

      try {
        const json = JSON.parse(dataStr);
        const text = json.candidates?.[0]?.content?.parts?.[0]?.text;
        if (text) {
          yield { type: "text", text };
        }
      } catch {
        // ignore
      }
    }
  }
}
