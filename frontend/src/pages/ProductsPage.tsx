import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { catalogue, detailOf, isStale, type PageResponse, type Product } from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import ListPage from '../components/ListPage';
import { countOf } from '../components/countOf';
import Pager from '../components/Pager';
import ProductFields from './ProductFields';
import { productFields, toForm, type Form } from './productForm';
import './addclient.css';
import './consultants.css';

const SIZE = 25;

const money = (n: number | null) =>
  n === null ? 'Negotiated' : `R ${n.toLocaleString('en-ZA', { minimumFractionDigits: 2 })}`;

interface PanelProps {
  product: Product;
  onSaved: (p: Product) => void;
  onClose: () => void;
}

function ProductPanel({ product, onSaved, onClose }: PanelProps) {
  const [v, setV] = useState<Form>(toForm(product));
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [current, setCurrent] = useState<Product>(product);

  const staleText = 'Someone else changed this product. Close this panel, reopen it and try again.';

  async function save(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setError(null);
    setOk(null);
    const checked = productFields(v);
    if (checked.error !== undefined) return setError(checked.error);
    setBusy(true);
    try {
      const saved = await catalogue.updateProduct(current.id, {
        ...checked.fields,
        version: current.version,
      });
      onSaved(saved);
      setCurrent(saved);
      setV(toForm(saved));
      setOk('Saved.');
    } catch (err) {
      setError(isStale(err) ? staleText : (detailOf(err) ?? 'Could not save the product.'));
    } finally {
      setBusy(false);
    }
  }

  async function toggleActive() {
    if (busy) return;
    setBusy(true);
    setError(null);
    setOk(null);
    try {
      const saved = await catalogue.setProductActive(current.id, !current.active, current.version);
      onSaved(saved);
      setCurrent(saved);
      setOk(saved.active ? 'The product is active.' : 'The product is deactivated.');
    } catch (err) {
      setError(isStale(err) ? staleText : (detailOf(err) ?? 'Could not change the product.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="cn-panel" aria-label={`Edit ${current.name}`}>
      <h2>Edit {current.name}</h2>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {ok && (
        <p className="alert ok" role="status">
          {ok}
        </p>
      )}
      <form onSubmit={save} noValidate>
        <ProductFields v={v} onChange={setV} codeLocked />
        <div className="cn-actions">
          <button type="submit" className="btn primary" disabled={busy}>
            Save changes
          </button>
          <button type="button" className="btn" onClick={toggleActive} disabled={busy}>
            {current.active ? 'Deactivate' : 'Activate'}
          </button>
          <button type="button" className="btn" onClick={onClose}>
            Close
          </button>
        </div>
      </form>
    </section>
  );
}

export default function ProductsPage() {
  const session = useSession();
  const isAdmin = hasAnyRole(session, ['ADMIN']);
  const canAdd = isAdmin; // only an admin adds products
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [state, setState] = useState<'all' | 'active' | 'inactive'>('all');
  const [pageNo, setPageNo] = useState(0);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<PageResponse<Product> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [panel, setPanel] = useState<Product | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setQuery(search.trim());
      setPageNo(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  useEffect(() => {
    let live = true;
    catalogue
      .listProducts({
        search: query,
        active: state === 'all' ? undefined : state === 'active',
        page: pageNo,
        size: SIZE,
      })
      .then((r) => {
        if (!live) return;
        setResult(r);
        setError(null);
      })
      .catch(() => live && setError('Could not load products.'));
    return () => {
      live = false;
    };
  }, [query, state, pageNo, reload]);

  return (
    <ListPage
      title="Products"
      description="The columns of the weekly register. Products are never deleted, only deactivated."
      action={
        canAdd && (
          <Link className="btn primary" to="/products/new">
            Add product
          </Link>
        )
      }
    >
      <section className="lp-card" aria-labelledby="lp-title">
        <div className="lp-head">
          <h2 id="lp-title">Product catalogue</h2>
          {result && <span className="lp-count">{countOf(result.totalItems, 'product')}</span>}
          <div className="lp-filters">
            <input
              type="search"
              placeholder="Search by name or code"
              aria-label="Search products"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <select
              aria-label="Filter by state"
              value={state}
              onChange={(e) => {
                setState(e.target.value as 'all' | 'active' | 'inactive');
                setPageNo(0);
              }}
            >
              <option value="all">All products</option>
              <option value="active">Active</option>
              <option value="inactive">Inactive</option>
            </select>
          </div>
        </div>
        {error && (
          <p className="alert error" role="alert">
            {error}
          </p>
        )}
        {!error && result === null && <p className="lp-empty">Loading...</p>}
        {result && result.items.length === 0 && (
          <p className="lp-empty">
            {!query && state === 'all'
              ? 'No products yet. The first weekly import adds them from the sheet’s columns; add any the sheet does not have with the button above.'
              : 'No products found.'}
          </p>
        )}
        {result && result.items.length > 0 && (
          <>
            <div className="lp-table">
              <table>
                <thead>
                  <tr>
                    <th>Code</th>
                    <th>Name</th>
                    <th className="num">Standard price</th>
                    <th>Counts toward</th>
                    <th>Status</th>
                    <th>
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {result.items.map((p) => (
                    <tr key={p.id} className={panel?.id === p.id ? 'lp-selected' : undefined}>
                      <td className="lp-code">{p.code}</td>
                      <td className="lp-strong">{p.name}</td>
                      <td className={p.standardPrice === null ? 'num lp-none' : 'num'}>
                        {money(p.standardPrice)}
                      </td>
                      <td>
                        {p.countsTowardQuotation && <span className="lp-pill">Quotation</span>}
                        {p.countsTowardBanked && <span className="lp-pill">Banked</span>}
                        {!p.countsTowardQuotation && !p.countsTowardBanked && (
                          <span className="lp-none">Neither</span>
                        )}
                      </td>
                      <td>
                        <span className={`badge ${p.active ? 'active' : 'inactive'}`}>
                          {p.active ? 'active' : 'inactive'}
                        </span>
                      </td>
                      <td>
                        {isAdmin && (
                          <button
                            type="button"
                            className="btn lp-row-btn"
                            onClick={() => setPanel(p)}
                          >
                            Edit
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager
              page={result.page}
              size={result.size}
              totalItems={result.totalItems}
              totalPages={result.totalPages}
              onPage={setPageNo}
            />
          </>
        )}
      </section>

      {panel && (
        <ProductPanel
          key={panel.id}
          product={panel}
          onSaved={() => setReload((n) => n + 1)}
          onClose={() => setPanel(null)}
        />
      )}
    </ListPage>
  );
}
