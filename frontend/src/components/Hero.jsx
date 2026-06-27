import { motion } from 'framer-motion';

export default function Hero() {
  return (
    <div className="text-center mb-12">
      <motion.div
        initial={{ opacity: 0, scale: 0.8 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.5 }}
        className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full glass text-xs font-medium text-[var(--color-text-secondary)] mb-8"
      >
        <span className="w-2 h-2 rounded-full bg-[var(--color-verdict-true)] animate-pulse" />
        AI-Powered Fact Verification
      </motion.div>

      <motion.h1
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6, delay: 0.1 }}
        className="text-5xl md:text-6xl font-extrabold tracking-tight mb-6"
      >
        Reel{' '}
        <span className="text-gradient">Fact-Checker</span>
      </motion.h1>

      <motion.p
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6, delay: 0.2 }}
        className="text-lg text-[var(--color-text-secondary)] max-w-xl mx-auto leading-relaxed"
      >
        Paste a YouTube Short or Instagram Reel URL and get an instant,
        AI-generated fact-check report with verified sources.
      </motion.p>
    </div>
  );
}
