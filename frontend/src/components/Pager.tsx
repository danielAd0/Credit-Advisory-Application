interface PagerProps {
  page: number; // 0-based
  size: number;
  totalItems: number;
  totalPages: number;
  onPage: (page: number) => void;
}

/** "Showing 1-25 of 134" with Previous and Next. Renders nothing when everything fits on one page. */
export default function Pager({ page, size, totalItems, totalPages, onPage }: PagerProps) {
  if (totalPages <= 1) return null;
  const from = page * size + 1;
  const to = Math.min(totalItems, (page + 1) * size);
  return (
    <nav className="pager" aria-label="Pages">
      <span className="muted">
        Showing {from}-{to} of {totalItems}
      </span>
      <div className="pager-buttons">
        <button
          type="button"
          className="btn"
          onClick={() => onPage(page - 1)}
          disabled={page === 0}
        >
          Previous
        </button>
        <span className="muted">
          Page {page + 1} of {totalPages}
        </span>
        <button
          type="button"
          className="btn"
          onClick={() => onPage(page + 1)}
          disabled={page + 1 >= totalPages}
        >
          Next
        </button>
      </div>
    </nav>
  );
}
