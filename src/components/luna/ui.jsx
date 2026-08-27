/**
 * Shared primitives for the Luna interface.
 *
 * Icons are FontAwesome classes (bundled locally — the app must render with no
 * network). The mascot is the only object in the app allowed to carry colour.
 */

import { useEffect } from 'react';

/** FontAwesome glyph. `b` switches to the brands face. */
export function I({ n, b = false, spin = false, className = '', ...rest }) {
  const face = b ? 'fa-brands' : 'fa-solid';
  return <i className={`${face} fa-${n}${spin ? ' fa-spin' : ''}${className ? ` ${className}` : ''}`} aria-hidden="true" {...rest} />;
}

/** The Luna mascot — a crescent with two closed, sleeping eyes. */
export function Mark({ size = 32, tone = 'moon', style }) {
  const fill = tone === 'chip' ? '#00c46a' : '#7c5cff';
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" style={{ flex: '0 0 auto', ...style }} aria-hidden="true">
      {tone === 'chip'
        ? <rect x="8" y="8" width="84" height="84" rx="28" fill={fill} />
        : <path d="M52 5c25 0 43 20 43 45 0 27-20 45-46 45C24 95 5 74 5 49 5 23 26 5 52 5z" fill={fill} />}
      <rect x="36" y={tone === 'chip' ? 38 : 36} width="9" height={tone === 'chip' ? 22 : 26} rx="4.5" fill="#fff" transform="rotate(-11 40 49)" />
      <rect x="56" y={tone === 'chip' ? 38 : 36} width="9" height={tone === 'chip' ? 22 : 26} rx="4.5" fill="#fff" transform="rotate(11 60 49)" />
    </svg>
  );
}

/** Bottom sheet. Every secondary surface in the app is one of these. */
export function Sheet({ open, title, onClose, children, action }) {
  useEffect(() => {
    if (!open) return undefined;
    const onKey = e => { if (e.key === 'Escape') onClose?.(); };
    window.addEventListener?.('keydown', onKey);
    return () => window.removeEventListener?.('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <>
      <div className="veil" onClick={onClose} />
      <div className="sheet" role="dialog" aria-modal="true" aria-label={title}>
        <div className="handle" />
        <div className="sh">
          <b>{title}</b>
          {action}
          <button type="button" className="ib" onClick={onClose} aria-label="Close">
            <I n="xmark" />
          </button>
        </div>
        <div className="body">{children}</div>
      </div>
    </>
  );
}
