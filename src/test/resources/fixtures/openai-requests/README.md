# OpenAI request fixtures (v4 B.1)

Request bodies in the shapes real clients send to `POST /v1/chat/completions`.
`OpenAiMapperTest` deserialises every file here and maps it to the domain request;
a new client shape gets a file and a line below.

| File | Client shape it mirrors | Where the shape was taken from (read 2026-10-08) |
|---|---|---|
| `openai-python-string.json` | OpenAI Python SDK: `content` as a plain string, `developer` role | The Chat Completions example of the SDK README, <https://github.com/openai/openai-python/blob/main/README.md> |
| `vercel-ai-multipart-text.json` | Vercel AI SDK (`@ai-sdk/openai-compatible`): a user turn with several text parts becomes a `content` array; streaming with `include_usage` | `convert-to-openai-compatible-chat-messages.ts` — one text part is sent as a string, anything else as an array of `{type: "text", text}` parts, <https://github.com/vercel/ai/blob/main/packages/openai-compatible/src/chat/convert-to-openai-compatible-chat-messages.ts> |
| `chat-ui-image.json` | A chat UI attaching a picture: a text part then an `image_url` part with a base64 `data:` URI | The `image_url` part shape `{type: "image_url", image_url: {url}}`, with an inline data URI, as built by the same Vercel converter; `detail` from the OpenAI Chat Completions API reference, <https://platform.openai.com/docs/api-reference/chat/create> |
| `langchain-tool-calling-turn.json` | LangChain (`langchain-openai`) tool-calling turn: an assistant message with `tool_calls` and `content: null`, then a `tool` message, with `tools` declared | `_convert_message_to_dict` in `langchain_openai/chat_models/base.py` — content becomes `None` when tool calls are present; a tool message keeps only `content`, `role`, `tool_call_id`, <https://github.com/langchain-ai/langchain/blob/master/libs/partners/openai/langchain_openai/chat_models/base.py> |
| `developer-max-completion-tokens.json` | A reasoning-model request: `developer` role, `max_completion_tokens` instead of `max_tokens`, plus fields the gateway ignores (`reasoning_effort`, `store`) | The OpenAI Chat Completions API reference, where `max_tokens` is deprecated in favour of `max_completion_tokens`, <https://platform.openai.com/docs/api-reference/chat/create> |

The image is a 1×1 PNG. No fixture holds a real conversation or a real key.
