## AI USAGE

### Chat logs

AI chat logs are stored in the `.ai_chats/` directory.

- `.ai_chats/ai_assistant/` contains chats from the Intellij's AI Assistant plugin, exported using the plugin's built-in
  `dump chat` feature.
- `.ai_chats/claude_code/` contains a running conversation with Claude Code exported daily using the `/export` command.

### Models

For both the AI Assistant and Claude Code, the Claude 4.7 Opus model was used (with `xhigh` preset for Claude Code).