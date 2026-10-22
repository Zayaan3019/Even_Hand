import { useState, type ReactNode } from 'react';

export function Card({ title, children, className, action }: {
  title?: ReactNode;
  children: ReactNode;
  className?: string;
  action?: ReactNode;
}) {
  return (
    <section className={`card ${className ?? ''}`}>
      {(title || action) && (
        <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
          {title && <h3>{title}</h3>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

export function Tile({ value, label, tone }: { value: ReactNode; label: string; tone?: 'hl' | 'accent' }) {
  return (
    <div className={`tile ${tone ?? ''}`}>
      <div className="v">{value}</div>
      <div className="k">{label}</div>
    </div>
  );
}

export function Badge({ tone, children }: { tone: 'red' | 'amber' | 'green' | 'blue'; children: ReactNode }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

/** A collapsed one-paragraph explanation, so nobody needs a manual to read a chart or a check. */
export function HowToRead({ children }: { children: ReactNode }) {
  return (
    <details className="how">
      <summary>How to read this</summary>
      <div>{children}</div>
    </details>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

/** A small tooltip that follows the pointer; charts call show/hide. */
export function useTooltip() {
  const [tip, setTip] = useState<{ x: number; y: number; text: string } | null>(null);
  const node = tip ? (
    <div className="tooltip" role="tooltip" style={{ left: tip.x + 14, top: tip.y + 14 }}>
      {tip.text}
    </div>
  ) : null;
  return {
    node,
    show: (e: { clientX: number; clientY: number }, text: string) => setTip({ x: e.clientX, y: e.clientY, text }),
    hide: () => setTip(null),
  };
}

/** A table header that sorts its column on click, with the arrow and aria-sort a screen reader needs. */
export function SortHeader<K extends string>({ k, label, sort, onSort }: {
  k: K;
  label: string;
  sort: { key: K; dir: 1 | -1 };
  onSort: (k: K) => void;
}) {
  const active = sort.key === k;
  return (
    <th className="sortable" aria-sort={active ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'}
        onClick={() => onSort(k)}>
      {label} {active ? (sort.dir === 1 ? '▲' : '▼') : ''}
    </th>
  );
}
