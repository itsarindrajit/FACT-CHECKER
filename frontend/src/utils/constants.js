export const STAGES = [
  { key: 'VALIDATING_URL', label: 'Validating', icon: '🔗' },
  { key: 'DOWNLOADING_AUDIO', label: 'Downloading', icon: '⬇️' },
  { key: 'TRANSCRIBING', label: 'Transcribing', icon: '🎙️' },
  { key: 'EXTRACTING_CLAIMS', label: 'Extracting Claims', icon: '🔍' },
  { key: 'SEARCHING_WEB', label: 'Searching Web', icon: '🌐' },
  { key: 'FINALIZING_REPORT', label: 'Finalizing', icon: '📊' },
];

export const VERDICT_COLORS = {
  TRUE: {
    bg: 'rgba(34, 197, 94, 0.1)',
    border: 'rgba(34, 197, 94, 0.4)',
    text: '#22c55e',
    badge: '#166534',
    badgeText: '#bbf7d0',
    label: 'True',
    icon: '✅',
  },
  FALSE: {
    bg: 'rgba(239, 68, 68, 0.1)',
    border: 'rgba(239, 68, 68, 0.4)',
    text: '#ef4444',
    badge: '#7f1d1d',
    badgeText: '#fecaca',
    label: 'False',
    icon: '❌',
  },
  MISLEADING: {
    bg: 'rgba(245, 158, 11, 0.1)',
    border: 'rgba(245, 158, 11, 0.4)',
    text: '#f59e0b',
    badge: '#78350f',
    badgeText: '#fef3c7',
    label: 'Misleading',
    icon: '⚠️',
  },
};

export const API_BASE = '/api';
