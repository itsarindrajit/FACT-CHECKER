import { useState, useCallback, useRef } from 'react';
import { API_BASE } from '../utils/constants';

/**
 * Custom hook for managing the fact-check SSE pipeline.
 * Sends a POST request and reads the SSE stream for real-time progress updates.
 */
export function useFactCheck() {
  const [stage, setStage] = useState(null);
  const [message, setMessage] = useState('');
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const abortRef = useRef(null);

  const startFactCheck = useCallback(async (url) => {
    // Reset state
    setStage(null);
    setMessage('');
    setReport(null);
    setError(null);
    setIsLoading(true);

    // Abort any previous request
    if (abortRef.current) {
      abortRef.current.abort();
    }
    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const response = await fetch(`${API_BASE}/fact-check`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errData = await response.json().catch(() => ({}));
        throw new Error(errData.error || `Server error: ${response.status}`);
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const jsonStr = line.slice(5).trim();
            if (!jsonStr) continue;

            try {
              const event = JSON.parse(jsonStr);

              if (event.stage === 'ERROR') {
                setError(event.error || event.message || 'An unknown error occurred');
                setIsLoading(false);
                return;
              }

              if (event.stage === 'COMPLETE') {
                setReport(event.report);
                setStage('COMPLETE');
                setMessage('Analysis complete!');
                setIsLoading(false);
                return;
              }

              setStage(event.stage);
              setMessage(event.message || '');
            } catch (parseErr) {
              // Ignore non-JSON lines (comments, keep-alives)
            }
          }
        }
      }

      // If we exited the loop without COMPLETE, something went wrong
      if (!report) {
        setIsLoading(false);
      }
    } catch (err) {
      if (err.name === 'AbortError') return;
      setError(err.message || 'Failed to connect to the server');
      setIsLoading(false);
    }
  }, []);

  const reset = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort();
    }
    setStage(null);
    setMessage('');
    setReport(null);
    setError(null);
    setIsLoading(false);
  }, []);

  return {
    stage,
    message,
    report,
    error,
    isLoading,
    startFactCheck,
    reset,
  };
}
