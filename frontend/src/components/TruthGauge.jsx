import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';

export default function TruthGauge({ score }) {
  const [animatedScore, setAnimatedScore] = useState(0);

  useEffect(() => {
    // Animate the score counting up
    const duration = 1500;
    const start = performance.now();
    const animate = (now) => {
      const elapsed = now - start;
      const progress = Math.min(elapsed / duration, 1);
      // Ease out cubic
      const eased = 1 - Math.pow(1 - progress, 3);
      setAnimatedScore(Math.round(eased * score));
      if (progress < 1) requestAnimationFrame(animate);
    };
    requestAnimationFrame(animate);
  }, [score]);

  // SVG circle params
  const size = 150;
  const strokeWidth = 10;
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (animatedScore / 100) * circumference;

  // Color based on score
  const getColor = (s) => {
    if (s >= 70) return { main: '#22c55e', glow: 'rgba(34,197,94,0.4)' };
    if (s >= 40) return { main: '#f59e0b', glow: 'rgba(245,158,11,0.4)' };
    return { main: '#ef4444', glow: 'rgba(239,68,68,0.4)' };
  };

  const color = getColor(animatedScore);

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.8 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.6, ease: 'easeOut' }}
      className="flex flex-col items-center group"
    >
      <div className="relative" style={{ width: size, height: size }}>
        {/* Background Glow */}
        <div 
          className="absolute inset-0 rounded-full blur-2xl transition-all duration-1000"
          style={{ backgroundColor: color.main, opacity: 0.25 }}
        ></div>

        <svg width={size} height={size} className="transform -rotate-90 relative z-10">
          {/* Background circle */}
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke="rgba(255,255,255,0.03)"
            strokeWidth={strokeWidth}
          />
          {/* Progress arc */}
          <motion.circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke={color.main}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
            strokeDasharray={circumference}
            initial={{ strokeDashoffset: circumference }}
            animate={{ strokeDashoffset: offset }}
            transition={{ duration: 1.5, ease: 'easeOut' }}
            style={{
              filter: `drop-shadow(0 0 12px ${color.glow})`,
            }}
          />
        </svg>

        {/* Center text */}
        <div className="absolute inset-0 flex flex-col items-center justify-center z-20">
          <div className="flex items-baseline">
            <span
              className="text-5xl font-black tabular-nums tracking-tighter"
              style={{ color: color.main, textShadow: `0 0 20px ${color.glow}` }}
            >
              {animatedScore}
            </span>
            <span className="text-lg font-bold text-[var(--color-text-muted)] ml-0.5">
              %
            </span>
          </div>
          <span className="text-[10px] font-black text-[var(--color-text-secondary)] uppercase tracking-[0.2em] mt-1">
            Accuracy
          </span>
        </div>
      </div>

      <div className="mt-6 glass px-4 py-1.5 rounded-full border border-white/5 flex items-center gap-2">
        <div className="w-2 h-2 rounded-full animate-pulse" style={{ backgroundColor: color.main }}></div>
        <p className="text-xs font-bold text-[var(--color-text-secondary)] uppercase tracking-widest">
          AI Integrity Analysis
        </p>
      </div>
    </motion.div>
  );
}
