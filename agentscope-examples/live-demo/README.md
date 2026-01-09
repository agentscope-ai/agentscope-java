# AgentScope Live Demo

Real-time voice conversation demo using AgentScope's LiveAgent API.

## Overview

This demo showcases the LiveAgent capabilities for real-time voice conversation with AI models. It includes:

- **CLI Demo**: Command-line interface for testing LiveAgent
- **Web Demo**: Spring Boot WebFlux application with WebSocket support

## Supported Providers

| Provider | Model | Environment Variable |
|----------|-------|---------------------|
| DashScope | qwen-omni-turbo-realtime | `DASHSCOPE_API_KEY` |
| OpenAI | gpt-4o-realtime-preview | `OPENAI_API_KEY` |
| Gemini | gemini-2.0-flash-exp | `GEMINI_API_KEY` |
| Doubao | doubao-realtime | `DOUBAO_API_KEY` |

## Prerequisites

- JDK 17 or higher
- Maven 3.6+
- API key for your chosen provider

## Quick Start

### 1. Build the Project

First, build the main AgentScope project:

```bash
# From the repository root
mvn clean install -DskipTests
```

### 2. Set Environment Variables

```bash
# For DashScope (default)
export DASHSCOPE_API_KEY=your-api-key

# Or for other providers
export OPENAI_API_KEY=your-api-key
export GEMINI_API_KEY=your-api-key
export DOUBAO_API_KEY=your-api-key
```

### 3. Run the Demo

#### Web Demo (Recommended)

```bash
cd agentscope-examples/live-demo
mvn spring-boot:run
```

Then open your browser at: http://localhost:8080

#### CLI Demo

```bash
cd agentscope-examples/live-demo
mvn exec:java -Dexec.mainClass="io.agentscope.demo.live.LiveCliDemo"
```

## Configuration

### Web Demo Configuration

Edit `src/main/resources/application.yml` or use environment variables:

```yaml
live:
  # Provider: dashscope, openai, gemini, doubao
  provider: ${LIVE_PROVIDER:dashscope}
  
  model:
    name: ${LIVE_MODEL_NAME:qwen-omni-turbo-realtime}
    api-key: ${DASHSCOPE_API_KEY:}
  
  agent:
    name: voice-assistant
    system-prompt: "You are a friendly voice assistant."
  
  session:
    voice: Cherry
    auto-reconnect: true
```

### CLI Demo Arguments

```bash
# Use different provider
mvn exec:java -Dexec.mainClass="io.agentscope.demo.live.LiveCliDemo" \
    -Dexec.args="--provider=openai --model=gpt-4o-realtime-preview"

# Specify API key directly
mvn exec:java -Dexec.mainClass="io.agentscope.demo.live.LiveCliDemo" \
    -Dexec.args="--api-key=sk-xxx"
```

## Web Demo Features

### Turn Detection Modes

The demo supports two turn detection modes:

#### VAD Mode (Auto)
- Default mode using Voice Activity Detection
- Speak naturally - the AI automatically detects when you stop talking
- Best for natural conversation flow

#### Manual Mode (Push-to-Talk)
- Click and hold "Hold to Talk" button while speaking
- Release the button when done
- Click "Done Speaking" to commit audio and trigger AI response
- Click "Clear" to discard recorded audio without sending
- Best for noisy environments or precise control

### Audio Input
- Click "Start Conversation" to begin
- Allow microphone access when prompted
- In VAD mode: speak naturally - audio is streamed in real-time
- In Manual mode: hold the PTT button while speaking

### Text Input
- Type messages in the text input field
- Press Enter or click "Send" to send

### Controls
- **Start Conversation**: Connect to the AI and start recording
- **Stop**: End the session and disconnect
- **VAD/Manual Toggle**: Switch between turn detection modes
- **Hold to Talk** (Manual mode): Press and hold while speaking
- **Done Speaking** (Manual mode): Commit audio and trigger response
- **Clear** (Manual mode): Discard recorded audio

## CLI Demo Commands

| Command | Description |
|---------|-------------|
| `<text>` | Send a text message |
| `/audio <base64>` | Send Base64-encoded audio data |
| `/interrupt` | Interrupt the current response |
| `/quit` | Exit the demo |

## WebSocket Protocol

### Client → Server Messages

```json
// Audio data (PCM 16kHz mono, Base64 encoded)
{"type": "audio", "data": "base64-encoded-pcm-data"}

// Text message
{"type": "text", "data": "Hello, how are you?"}

// Interrupt current response
{"type": "interrupt"}

// Manual mode: commit audio buffer
{"type": "commit"}

// Manual mode: trigger response generation
{"type": "createResponse"}

// Manual mode: clear audio buffer
{"type": "clear"}
```

### Server → Client Messages

```json
// Session lifecycle
{"type": "SESSION_CREATED", "message": {...}}
{"type": "SESSION_UPDATED", "message": {...}}

// Response data
{"type": "TEXT_DELTA", "message": {"text": "Hello"}}
{"type": "AUDIO_DELTA", "message": {"audio": "base64-pcm"}}

// Transcription
{"type": "INPUT_TRANSCRIPTION", "message": {"text": "user speech"}}
{"type": "OUTPUT_TRANSCRIPTION", "message": {"text": "assistant speech"}}

// Turn complete
{"type": "TURN_COMPLETE", "message": {...}}

// Errors
{"type": "ERROR", "message": "error description"}
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Browser (index.html)                                       │
│  ├── MediaRecorder API (audio capture)                      │
│  ├── WebSocket client                                       │
│  └── Audio playback                                         │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ WebSocket (ws://localhost:8080/live-chat)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Spring Boot WebFlux                                        │
│  ├── LiveWebSocketHandler                                   │
│  │   ├── Parse client messages                              │
│  │   ├── Create Msg objects                                 │
│  │   └── Serialize LiveEvents                               │
│  └── WebSocketConfig                                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ Flux<Msg> / Flux<LiveEvent>
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  AgentScope Core                                            │
│  ├── LiveAgent                                              │
│  │   ├── Reconnection logic                                 │
│  │   ├── Tool call handling                                 │
│  │   └── State management                                   │
│  └── LiveModel (DashScope/OpenAI/Gemini/Doubao)            │
│      ├── WebSocket connection                               │
│      └── Protocol formatting                                │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ WebSocket
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  LLM Provider API                                           │
│  (DashScope / OpenAI / Gemini / Doubao)                    │
└─────────────────────────────────────────────────────────────┘
```

## Troubleshooting

### "API key not configured"
Make sure you've set the appropriate environment variable for your provider.

### "Microphone access denied"
- Check browser permissions
- Ensure you're using HTTPS in production (required for microphone access)
- The demo falls back to text input if microphone is unavailable

### "Connection failed"
- Verify your API key is valid
- Check network connectivity
- Review server logs for detailed error messages

### Audio quality issues
- Ensure your microphone is working properly
- Check that no other application is using the microphone
- The demo uses 16kHz mono PCM format

## Development

### Building

```bash
mvn clean compile
```

### Running Tests

```bash
mvn test
```

### Code Formatting

```bash
mvn spotless:apply
```

## License

Apache License 2.0
