export default function Footer() {
  return (
    <footer className="text-center py-8 mt-16">
      <div className="flex items-center justify-center gap-2 text-xs text-[var(--color-text-secondary)]">
        <span>Powered by</span>
        <span className="font-semibold text-gradient">Gemini AI</span>
        <span>•</span>
        <span className="font-semibold">Groq Whisper</span>
        <span>•</span>
        <span className="font-semibold">Tavily Search</span>
      </div>
      <p className="text-xs text-[rgba(255,255,255,0.2)] mt-2">
        Reel Fact-Checker © {new Date().getFullYear()}. Results are AI-generated and should be verified independently.
      </p>
    </footer>
  );
}
