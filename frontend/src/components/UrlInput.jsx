import { useState } from 'react';
import { motion } from 'framer-motion';

export default function UrlInput({ onSubmit, isLoading }) {
  const [url, setUrl] = useState('');
  const [validationMsg, setValidationMsg] = useState('');

  const ytRegex = /(?:youtube\.com\/(?:shorts\/|watch\?v=)|youtu\.be\/)/;
  const instaRegex = /instagram\.com\/(?:reel|reels)\//;

  const handlePaste = (e) => {
    const pasted = e.clipboardData.getData('text');
    setUrl(pasted);
    validateUrl(pasted);
  };

  const validateUrl = (value) => {
    if (!value.trim()) {
      setValidationMsg('');
      return false;
    }
    if (ytRegex.test(value)) {
      setValidationMsg('✓ YouTube link detected');
      return true;
    }
    if (instaRegex.test(value)) {
      setValidationMsg('✓ Instagram Reel detected');
      return true;
    }
    setValidationMsg('⚠ Please enter a valid YouTube Shorts or Instagram Reels URL');
    return false;
  };

  const handleChange = (e) => {
    setUrl(e.target.value);
    validateUrl(e.target.value);
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!url.trim() || isLoading) return;
    if (!ytRegex.test(url) && !instaRegex.test(url)) {
      setValidationMsg('⚠ Please enter a valid YouTube Shorts or Instagram Reels URL');
      return;
    }
    onSubmit(url.trim());
  };

  const isValid = ytRegex.test(url) || instaRegex.test(url);

  return (
    <motion.form
      onSubmit={handleSubmit}
      className="w-full max-w-2xl mx-auto px-4"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, delay: 0.3 }}
    >
      <div className="relative group">
        {/* Outer Glow Effect on Hover */}
        <div className="absolute -inset-1 bg-gradient-to-r from-[var(--color-accent-purple)] to-[var(--color-accent-cyan)] rounded-2xl blur opacity-15 group-focus-within:opacity-30 transition duration-500"></div>
        
        <div className="relative glass rounded-2xl p-1.5 flex flex-col sm:flex-row items-center gap-2 transition-all duration-300 group-focus-within:border-[var(--color-accent-purple)]/50 group-focus-within:shadow-[0_0_20px_rgba(124,58,237,0.15)]">
          <div className="hidden sm:flex pl-4 items-center text-[var(--color-text-secondary)]">
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
          </div>
          
          <input
            id="url-input"
            type="url"
            value={url}
            onChange={handleChange}
            onPaste={handlePaste}
            placeholder="Paste a YouTube Short or Instagram Reel URL..."
            disabled={isLoading}
            className="w-full flex-1 bg-transparent px-5 py-3.5 text-[var(--color-text-primary)] placeholder:text-[var(--color-text-secondary)] outline-none text-base font-medium"
            autoComplete="off"
          />

          <motion.button
            id="submit-btn"
            type="submit"
            disabled={!isValid || isLoading}
            whileHover={{ scale: isValid && !isLoading ? 1.02 : 1, filter: 'brightness(1.1)' }}
            whileTap={{ scale: isValid && !isLoading ? 0.98 : 1 }}
            className="w-full sm:w-auto px-8 py-3.5 sm:h-[54px] rounded-xl font-bold text-sm text-white transition-all duration-300 shadow-lg disabled:opacity-30 disabled:cursor-not-allowed flex-shrink-0 whitespace-nowrap"
            style={{
              background: isValid
                ? 'linear-gradient(135deg, var(--color-accent-purple), var(--color-accent-blue))'
                : 'rgba(255,255,255,0.05)',
              boxShadow: isValid && !isLoading ? '0 4px 15px rgba(124, 58, 237, 0.3)' : 'none',
            }}
          >
            {isLoading ? (
              <span className="flex items-center gap-2">
                <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                Processing...
              </span>
            ) : (
              'Fact-Check'
            )}
          </motion.button>
        </div>
      </div>

      {validationMsg && (
        <motion.p
          initial={{ opacity: 0, y: -5 }}
          animate={{ opacity: 1, y: 0 }}
          className="mt-4 text-sm font-medium text-center"
          style={{
            color: validationMsg.startsWith('✓')
              ? 'var(--color-verdict-true)'
              : 'var(--color-verdict-misleading)',
          }}
        >
          {validationMsg}
        </motion.p>
      )}
    </motion.form>
  );
}
