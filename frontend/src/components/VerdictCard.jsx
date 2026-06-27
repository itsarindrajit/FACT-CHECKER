import { motion } from 'framer-motion';
import { VERDICT_COLORS } from '../utils/constants';

export default function VerdictCard({ verdict, index }) {
  const colors = VERDICT_COLORS[verdict.verdict] || VERDICT_COLORS.MISLEADING;

  return (
    <motion.div
      initial={{ opacity: 0, y: 30, scale: 0.95 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ duration: 0.5, delay: index * 0.15, ease: 'easeOut' }}
      className="glass rounded-2xl py-7 pr-7 pl-8 transition-all duration-300 hover:shadow-xl hover:shadow-[var(--color-accent-purple)]/10 group w-full text-left relative overflow-hidden"
      style={{
        borderColor: colors.border,
        borderLeft: `4px solid ${colors.text}`,
        borderWidth: '1px',
        borderStyle: 'solid',
        background: `linear-gradient(145deg, ${colors.bg}, rgba(255,255,255,0.01))`,
      }}
    >
      {/* Hover Background */}
      <div className="absolute inset-0 bg-white opacity-0 group-hover:opacity-[0.02] transition-opacity duration-300 pointer-events-none" />

      {/* Header: Verdict badge + category */}
      <div className="flex items-center justify-between mb-5 relative z-10">
        <div className="flex items-center gap-3">
          <span
            className="w-10 h-10 rounded-xl flex items-center justify-center text-xl shadow-inner"
            style={{ backgroundColor: colors.badge }}
          >
            {colors.icon}
          </span>
          <div className="flex flex-col">
            <span 
              className="text-[11px] font-extrabold uppercase tracking-widest px-2.5 py-0.5 rounded-md border inline-block mb-1" 
              style={{ 
                color: colors.text, 
                backgroundColor: `${colors.text}1A`,
                borderColor: `${colors.text}33`
              }}
            >
              {colors.label}
            </span>
            <span className="text-[10px] font-bold text-[var(--color-text-muted)] uppercase tracking-[0.2em]">
              Verification Verdict
            </span>
          </div>
        </div>
        <span className="px-2 py-1 rounded bg-[rgba(255,255,255,0.05)] text-[10px] font-bold text-[var(--color-text-secondary)] uppercase tracking-widest border border-white/5">
          {verdict.category}
        </span>
      </div>

      {/* Claim text */}
      <div className="relative mb-4">
        <svg className="absolute -left-2 -top-2 w-6 h-6 text-[var(--color-text-muted)] opacity-20" fill="currentColor" viewBox="0 0 24 24">
          <path d="M14.017 21L14.017 18C14.017 16.8954 14.9124 16 16.017 16H19.017C19.5693 16 20.017 15.5523 20.017 15V9C20.017 8.44772 19.5693 8 19.017 8H16.017C14.9124 8 14.017 7.10457 14.017 6V3C14.017 2.44772 14.4647 2 15.017 2H21.017C21.5693 2 22.017 2.44772 22.017 3V15C22.017 18.3137 19.3307 21 16.017 21H14.017ZM3.017 21L3.017 18C3.017 16.8954 3.91243 16 5.017 16H8.017C8.56928 16 9.017 15.5523 9.017 15V9C9.017 8.44772 8.56928 8 8.017 8H5.017C3.91243 8 3.017 7.10457 3.017 6V3C3.017 2.44772 3.46472 2 4.017 2H10.017C10.5693 2 11.017 2.44772 11.017 3V15C11.017 18.3137 8.3307 21 5.017 21H3.017Z" />
        </svg>
        <p className="text-[var(--color-text-primary)] text-lg font-semibold leading-relaxed relative z-10 pl-2">
          {verdict.claim}
        </p>
      </div>

      {/* Explanation */}
      <div className="bg-white/[0.02] border border-white/5 rounded-xl p-4 mb-5">
        <p className="text-sm text-[var(--color-text-secondary)] leading-relaxed italic">
          {verdict.explanation}
        </p>
      </div>

      {/* Footer: Source + confidence */}
      <div className="flex items-end justify-between pt-5 border-t border-white/5 relative z-10 mt-2">
        <div className="flex flex-col items-start gap-1.5">
          <span className="text-[10px] font-bold text-[var(--color-text-muted)] uppercase tracking-widest">
            AI Confidence
          </span>
          <div className="flex items-center gap-3">
            <span className="text-sm font-black" style={{ color: colors.text }}>
              {Math.round(verdict.confidence * 100)}%
            </span>
            <div className="w-20 h-1.5 rounded-full bg-[rgba(255,255,255,0.06)] overflow-hidden shadow-inner">
              <motion.div
                initial={{ width: 0 }}
                animate={{ width: `${verdict.confidence * 100}%` }}
                transition={{ duration: 1, delay: index * 0.15 + 0.3 }}
                className="h-full rounded-full"
                style={{ backgroundColor: colors.text, boxShadow: `0 0 10px ${colors.text}66` }}
              />
            </div>
          </div>
        </div>

        {verdict.sourceUrl && (
          <a
            href={verdict.sourceUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="group/link text-xs font-bold transition-all duration-300 flex items-center gap-2 px-4 py-2 rounded-xl bg-[rgba(255,255,255,0.03)] hover:bg-[rgba(255,255,255,0.08)] border border-white/5 hover:border-white/10 shadow-sm"
            style={{ color: colors.text }}
          >
            Evidence Source
            <svg className="w-3.5 h-3.5 transition-transform group-hover/link:translate-x-0.5 group-hover/link:-translate-y-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5}
                d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
            </svg>
          </a>
        )}
      </div>
    </motion.div>
  );
}
