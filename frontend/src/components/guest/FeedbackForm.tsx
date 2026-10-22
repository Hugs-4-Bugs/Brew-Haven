/**
 * Post-order feedback — 1-5 stars + optional comment (≤500 chars).
 * One submission per bill (backend enforces ownership + idempotency);
 * this component shows an honest thank-you state once submitted.
 */

import { useState } from 'react';
import { submitFeedback } from '../../api/revenue';

export default function FeedbackForm({ billUuid }: { billUuid: string }): JSX.Element | null {
  const [rating, setRating] = useState(0);
  const [hover, setHover] = useState(0);
  const [comment, setComment] = useState('');
  const [state, setState] = useState<'idle' | 'busy' | 'done' | 'error'>('idle');
  const [message, setMessage] = useState('');

  if (!billUuid) return null;

  const send = async () => {
    if (rating < 1) {
      setState('error');
      setMessage('Pick a star rating first.');
      return;
    }
    setState('busy');
    try {
      await submitFeedback(billUuid, rating, comment.trim());
      setState('done');
    } catch (err) {
      setState('error');
      setMessage(err instanceof Error ? err.message : 'Could not submit feedback.');
    }
  };

  if (state === 'done') {
    return (
      <p className="feedback-done" role="status">
        ⭐ Thank you for rating your order — it helps our kitchen improve.
      </p>
    );
  }

  return (
    <div className="feedback-block" aria-label="Rate your order">
      <p className="feedback-title">How was your order?</p>
      <div className="feedback-stars" role="radiogroup" aria-label="Star rating">
        {[1, 2, 3, 4, 5].map((star) => (
          <button
            key={star}
            type="button"
            role="radio"
            aria-checked={rating === star}
            aria-label={`${star} star${star === 1 ? '' : 's'}`}
            className={`feedback-star ${(hover || rating) >= star ? 'feedback-star-on' : ''}`}
            onMouseEnter={() => setHover(star)}
            onMouseLeave={() => setHover(0)}
            onClick={() => setRating(star)}
          >
            ★
          </button>
        ))}
      </div>
      <textarea
        className="input feedback-comment"
        placeholder="Anything you'd like to tell the kitchen? (optional)"
        maxLength={500}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        rows={2}
      />
      {state === 'error' && <p className="field-error" role="alert">{message}</p>}
      <button type="button" className="btn btn-outline" onClick={send} disabled={state === 'busy'}>
        {state === 'busy' ? 'Sending…' : 'Send feedback'}
      </button>
    </div>
  );
}
