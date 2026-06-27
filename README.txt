# 🔍 Reel Fact-Checker

AI-powered fact-checker for YouTube Shorts and Instagram Reels. Paste a URL, get an instant fact-check report with verified sources.

## Tech Stack

- **Backend**: Spring Boot 3.5 (WebFlux) + Spring AI
- **Frontend**: React + Vite + Tailwind CSS v4 + Framer Motion
- **AI**: Gemini 1.5 Flash (reasoning) + Groq Whisper (transcription)
- **Search**: Tavily API (real-time web search)
- **Audio**: yt-dlp + ffmpeg

## Prerequisites

- **Java 21** (JDK)
- **Node.js 18+**
- **yt-dlp** and **ffmpeg** installed and in PATH
- API keys for Gemini, Groq, and Tavily

## Quick Start

### 1. Set Environment Variables

```bash
export GEMINI_API_KEY=your_key_here
export GROQ_API_KEY=your_key_here
export TAVILY_API_KEY=your_key_here
```

### 2. Start the Backend

```bash
cd backend
./mvnw spring-boot:run
```

### 3. Start the Frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 in your browser.

### 4. Docker (Production)

```bash
docker-compose up --build
```

## How It Works

1. **URL Validation** — Checks if the URL is a valid YouTube Short or Instagram Reel
2. **Audio Download** — yt-dlp extracts audio-only (saves bandwidth)
3. **Transcription** — Groq Whisper converts speech to text
4. **Claim Extraction** — Gemini identifies 3–5 factual claims
5. **Web Search** — Tavily searches for real-time evidence
6. **Verdict** — Gemini compares claims vs evidence → TRUE, FALSE, or MISLEADING

## License

MIT
