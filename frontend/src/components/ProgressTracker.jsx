import { motion } from 'framer-motion';
import { STAGES } from '../utils/constants';

export default function ProgressTracker({ currentStage, message }) {
  const currentIndex = STAGES.findIndex((s) => s.key === currentStage);

  return (
    <motion.div
      initial={{ opacity: 0, y: 30 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5 }}
      className="w-full max-w-2xl mx-auto my-10 min-h-[250px] flex flex-col justify-center"
    >
      {/* Stage Steps */}
      <div className="flex items-center justify-between mb-8 px-4">
        {STAGES.map((stage, index) => {
          const isCompleted = index < currentIndex;
          const isActive = index === currentIndex;
          const isPending = index > currentIndex;

          return (
            <div key={stage.key} className="flex items-center flex-1 last:flex-none">
              {/* Step circle */}
              <div className="flex flex-col items-center min-w-[64px]">
                <motion.div
                  animate={{
                    scale: isActive ? [1, 1.15, 1] : 1,
                    transition: isActive
                      ? { duration: 1.5, repeat: Infinity, ease: 'easeInOut' }
                      : {},
                  }}
                  className="w-12 h-12 rounded-full flex items-center justify-center text-base font-bold transition-all duration-500"
                  style={{
                    background: isCompleted
                      ? 'linear-gradient(135deg, var(--color-accent-purple), var(--color-accent-blue))'
                      : isActive
                        ? 'linear-gradient(135deg, var(--color-accent-purple), var(--color-accent-cyan))'
                        : 'rgba(255,255,255,0.06)',
                    boxShadow: isActive
                      ? '0 0 25px rgba(124,58,237,0.6), 0 0 50px rgba(124,58,237,0.3)'
                      : 'none',
                    color: isPending ? 'var(--color-text-secondary)' : '#fff',
                  }}
                >
                  {isCompleted ? '✓' : stage.icon}
                </motion.div>
                <span
                  className="mt-3 text-[11px] font-semibold text-center w-[72px] transition-colors duration-300 leading-tight"
                  style={{
                    color: isActive
                      ? '#ffffff'
                      : isCompleted
                        ? 'var(--color-accent-purple)'
                        : 'var(--color-text-muted)',
                  }}
                >
                  {stage.label}
                </span>
              </div>

              {/* Connector line */}
              {index < STAGES.length - 1 && (
                <div className="flex-1 h-0.5 mx-3 mt-[-20px] rounded-full overflow-hidden bg-[rgba(255,255,255,0.06)]">
                  <motion.div
                    initial={{ width: '0%' }}
                    animate={{ width: isCompleted ? '100%' : isActive ? '50%' : '0%' }}
                    transition={{ duration: 0.8, ease: 'easeOut' }}
                    className="h-full rounded-full"
                    style={{
                      background: 'linear-gradient(90deg, var(--color-accent-purple), var(--color-accent-cyan))',
                    }}
                  />
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Current status message */}
      {message && (
        <motion.div
          key={message}
          initial={{ opacity: 0, y: 5 }}
          animate={{ opacity: 1, y: 0 }}
          className="text-center"
        >
          <p className="text-sm text-[var(--color-text-secondary)] glass inline-block px-4 py-2 rounded-full">
            {message}
          </p>
        </motion.div>
      )}
    </motion.div>
  );
}
