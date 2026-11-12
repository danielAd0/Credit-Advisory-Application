import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import './listpage.css';

/**
 * The frame shared by the Team, Products, Employers and Branches pages: breadcrumb, title,
 * a one-line description, an optional main action, the page body and a footer.
 */
export default function ListPage({
  title,
  description,
  action,
  footer,
  children,
}: {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  /** The right-hand footer note. */
  footer?: string;
  children: ReactNode;
}) {
  return (
    <div className="lp">
      <nav className="lp-crumbs" aria-label="Breadcrumb">
        <Link to="/">Workspace</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{title}</span>
      </nav>
      <div className="lp-top">
        <div>
          <h1>{title}</h1>
          {description && <p className="lp-sub">{description}</p>}
        </div>
        {action && <div className="lp-action">{action}</div>}
      </div>

      {children}

      <footer className="lp-foot">
        <span>Vital Credit / {title}</span>
        <span>{footer ?? 'Internal workspace data'}</span>
      </footer>
    </div>
  );
}
