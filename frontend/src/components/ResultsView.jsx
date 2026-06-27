import { motion } from 'framer-motion';
import TruthGauge from './TruthGauge';
import VerdictCard from './VerdictCard';
import TranscriptPanel from './TranscriptPanel';

export default function ResultsView({ report, onReset }) {
  if (!report) return null;

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.5 }}
      className="w-full max-w-3xl mx-auto"
    >
      {/* Header with reset button */}
      <motion.div
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
        className="flex items-center justify-between mb-8"
      >
        <div>
          <h2 className="text-2xl font-bold text-[var(--color-text-primary)]">
            Fact-Check Report
          </h2>
          <div className="flex items-center gap-3 mt-1.5 text-xs font-medium text-[var(--color-text-muted)]">
            <span>{new Date(report.analyzedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}</span>
            <span className="w-1 h-1 rounded-full bg-white/20"></span>
            <span>{new Date(report.analyzedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
            {report.videoLength && (
              <>
                <span className="w-1 h-1 rounded-full bg-white/20"></span>
                <span>{report.videoLength}</span>
              </>
            )}
          </div>
        </div>
        <motion.button
          id="check-another-btn"
          onClick={onReset}
          whileHover={{ scale: 1.03 }}
          whileTap={{ scale: 0.97 }}
          className="px-4 py-2 rounded-lg glass text-sm font-medium text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)] transition-colors duration-200"
        >
          ← Check Another
        </motion.button>
      </motion.div>

      {/* URL analyzed */}
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.15 }}
        className="glass rounded-xl px-4 py-2.5 mb-8 text-center"
      >
        <span className="text-xs text-[var(--color-text-secondary)]">Analyzed: </span>
        <a
          href={report.url}
          target="_blank"
          rel="noopener noreferrer"
          className="text-xs text-[var(--color-accent-cyan)] hover:underline break-all"
        >
          {report.url}
        </a>
      </motion.div>

      {/* Truth Gauge */}
      <div className="flex justify-center w-full mb-12">
        <TruthGauge score={report.truthScore} />
      </div>

      {/* Verdict Cards */}
      <div className="flex flex-col gap-6 mb-10">
        {report.verdicts && report.verdicts.map((verdict, index) => (
          <VerdictCard key={verdict.claimId} verdict={verdict} index={index} />
        ))}
      </div>

      {/* Transcript */}
      <TranscriptPanel transcript={report.transcript} />

    </motion.div>
  );
}
