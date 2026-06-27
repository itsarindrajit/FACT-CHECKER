import { AnimatePresence, motion } from 'framer-motion';
import Hero from './components/Hero';
import UrlInput from './components/UrlInput';
import ProgressTracker from './components/ProgressTracker';
import ResultsView from './components/ResultsView';
import Footer from './components/Footer';
import { useFactCheck } from './hooks/useFactCheck';

export default function App() {
  const { stage, message, report, error, isLoading, startFactCheck, reset } = useFactCheck();

  const showResults = report && stage === 'COMPLETE';
  const showProgress = isLoading && !showResults;
  const showInput = !showResults;

  return (
    <div className="relative min-h-screen flex flex-col">
      {/* Animated background blobs */}
      <div className="animated-bg" />

      {/* Main content — grows to push footer down */}
      <main className={`relative z-10 px-4 flex-1 flex flex-col items-center w-full text-center ${showResults ? 'justify-start pt-12 pb-20' : 'justify-center pb-20'}`}>
        <div className="w-full max-w-3xl mx-auto flex flex-col items-center">
          <div className="w-full flex flex-col items-center justify-center">
          <AnimatePresence mode="wait">
            {showInput && (
              <motion.div
                key="input-section"
                className="w-full flex flex-col items-center"
                exit={{ opacity: 0, y: -30 }}
                transition={{ duration: 0.3 }}
              >
                <Hero />
                <UrlInput onSubmit={startFactCheck} isLoading={isLoading} />
              </motion.div>
            )}
          </AnimatePresence>

          {/* Progress tracker */}
          <AnimatePresence>
            {showProgress && (
              <motion.div
                key="progress"
                exit={{ opacity: 0, scale: 0.95 }}
                transition={{ duration: 0.3 }}
              >
                <ProgressTracker currentStage={stage} message={message} />
              </motion.div>
            )}
          </AnimatePresence>

          {/* Error message */}
          <AnimatePresence>
            {error && (
              <motion.div
                key="error"
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                className="max-w-2xl mx-auto mt-8"
              >
                <div
                  className="glass rounded-xl p-5 text-center"
                  style={{
                    borderColor: 'rgba(239, 68, 68, 0.4)',
                    borderWidth: '1px',
                    borderStyle: 'solid',
                    background: 'rgba(239, 68, 68, 0.08)',
                  }}
                >
                  <p className="text-[var(--color-verdict-false)] font-medium mb-2">
                    ❌ Something went wrong
                  </p>
                  <p className="text-sm text-[var(--color-text-secondary)]">{error}</p>
                  <button
                    onClick={reset}
                    className="mt-4 px-4 py-2 rounded-lg text-sm font-medium text-white transition-all duration-200"
                    style={{
                      background: 'linear-gradient(135deg, var(--color-accent-purple), var(--color-accent-blue))',
                    }}
                  >
                    Try Again
                  </button>
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          {/* Results */}
          <AnimatePresence>
            {showResults && (
              <motion.div
                key="results"
                initial={{ opacity: 0, y: 40 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.6 }}
              >
                <ResultsView report={report} onReset={reset} />
              </motion.div>
            )}
          </AnimatePresence>
          </div>
        </div>
      </main>

      {/* Footer pinned to bottom */}
      <div className="relative z-10">
        <Footer />
      </div>
    </div>
  );
}
