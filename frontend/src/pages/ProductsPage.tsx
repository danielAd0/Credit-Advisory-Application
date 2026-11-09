import { useEffect, useState, type FormEvent } from 'react';
import { catalogue, detailOf, isStale, type PageResponse, type Product } from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import Pager from '../components/Pager';
import './addclient.css';
import './consultants.css';

const SIZE = 25;

interface Form {
  code: string;
  name: string;
  price: string;
  quotation: boolean;
  banked: boolean;
  order: string;
}

const EMPTY: Form = { code: '', name: '', price: '', quotation: true, banked: true, order: '' };

const toForm = (p: Product): Form => ({
  code: p.code,
  name: p.name,
  price: p.standardPrice === null ? '' : String(p.standardPrice),
  quotation: p.countsTowardQuotation,
  banked: p.countsTowardBanked,
  order: String(p.displayOrder),
});

const money = (n: number | null) =>
  n === null ? 'Negotiated' : `R ${n.toLocaleString('en-ZA', { minimumFractionDigits: 2 })}`;

interface PanelProps {
  product: Product | null;
  onSaved: (p: Product, isNew: boolean) => void;
  onClose: () => void;
}

function ProductPanel({ product, onSaved, onClose }: PanelProps) {
  const [v, setV] = useState<Form>(product ? toForm(product) : EMPTY);
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [current, setCurrent] = useState<Product | null>(product);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setError(null);
    setOk(null);
    const price = v.price.trim() === '' ? null : Number(v.price);
    const order = v.order.trim() === '' ? 0 : Number(v.order);
    if (!v.name.trim()) return setError('Enter the product name.');
    if (!current && !v.code.trim()) return setError('Enter a product code.');
    if (price !== null && (!Number.isFinite(price) || price < 0))
      return setError('The price must be a number, or empty if it is negotiated.');
    if (!Number.isInteger(order)) return setError('The display order must be a whole number.');
    setBusy(true);
    try {
      const fields = {
        name: v.name.trim(),
        standardPrice: price,
        countsTowardQuotation: v.quotation,
        countsTowardBanked: v.banked,
        displayOrder: order,
      };
      const saved = current
        ? await catalogue.updateProduct(current.id, { ...fields, version: current.version })
        : await catalogue.createProduct({ ...fields, code: v.code.trim() });
      onSaved(saved, !current);
      setCurrent(saved);
      setV(toForm(saved));
      setOk('Saved.');
    } catch (err) {
      setError(
        isStale(err)
          ? 'Someone else changed this product. Close this panel, reopen it and try again.'
          : (detailOf(err) ?? 'Could not save the product.'),
      );
    } finally {
      setBusy(false);
    }
  }

  async function toggleActive() {
    if (busy || !current) return;
    setBusy(true);
    setError(null);
    setOk(null);
    try {
      const saved = await catalogue.setProductActive(current.id, !current.active, current.version);
      onSaved(saved, false);
      setCurrent(saved);
      setOk(saved.active ? 'The product is active.' : 'The product is deactivated.');
    } catch (err) {
      setError(
        isStale(err)
          ? 'Someone else changed this product. Close this panel, reopen it and try again.'
          : (detailOf(err) ?? 'Could not change the product.'),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="cn-panel" aria-label={current ? `Edit ${current.name}` : 'Add product'}>
      <h2>{current ? `Edit ${current.name}` : 'Add product'}</h2>
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
        <div className="ac-field">
          <label htmlFor="pr-code">Code</label>
          <input
            id="pr-code"
            value={v.code}
            disabled={!!current}
            onChange={(e) => setV({ ...v, code: e.target.value })}
            autoComplete="off"
          />
          <p className="ac-hint">
            {current
              ? 'The code never changes.'
              : 'Saved in capitals, for example "funeral cover" becomes FUNERAL_COVER.'}
          </p>
        </div>
        <div className="ac-field">
          <label htmlFor="pr-name">Name</label>
          <input
            id="pr-name"
            value={v.name}
            onChange={(e) => setV({ ...v, name: e.target.value })}
            autoComplete="off"
          />
        </div>
        <div className="ac-row">
          <div className="ac-field">
            <label htmlFor="pr-price">Standard price (R)</label>
            <input
              id="pr-price"
              inputMode="decimal"
              value={v.price}
              onChange={(e) => setV({ ...v, price: e.target.value })}
              autoComplete="off"
            />
            <p className="ac-hint">Leave empty if the price is negotiated.</p>
          </div>
          <div className="ac-field">
            <label htmlFor="pr-order">Display order</label>
            <input
              id="pr-order"
              inputMode="numeric"
              value={v.order}
              onChange={(e) => setV({ ...v, order: e.target.value })}
              autoComplete="off"
            />
          </div>
        </div>
        <p>
          <label>
            <input
              type="checkbox"
              checked={v.quotation}
              onChange={(e) => setV({ ...v, quotation: e.target.checked })}
            />{' '}
            Counts toward quotation
          </label>
          <br />
          <label>
            <input
              type="checkbox"
              checked={v.banked}
              onChange={(e) => setV({ ...v, banked: e.target.checked })}
            />{' '}
            Counts toward banked
          </label>
        </p>
        <div className="cn-actions">
          <button type="submit" className="btn primary" disabled={busy}>
            {current ? 'Save changes' : 'Add product'}
          </button>
          {current && (
            <button type="button" className="btn" onClick={toggleActive} disabled={busy}>
              {current.active ? 'Deactivate' : 'Activate'}
            </button>
          )}
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
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [state, setState] = useState<'all' | 'active' | 'inactive'>('all');
  const [pageNo, setPageNo] = useState(0);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<PageResponse<Product> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [panel, setPanel] = useState<{ product: Product | null } | null>(null);

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
    <>
      <div className="topbar-actions">
        <h1>Products</h1>
        {isAdmin && (
          <button type="button" className="btn primary" onClick={() => setPanel({ product: null })}>
            Add product
          </button>
        )}
      </div>
      <p className="muted">
        The columns of the weekly register. Products are never deleted, only deactivated.
      </p>

      <div className="cn-filters">
        <div className="toolbar">
          <input
            type="search"
            placeholder="Search by name or code"
            aria-label="Search products"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
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

      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {!error && result === null && <p className="muted">Loading...</p>}
      {result && result.items.length === 0 && <p className="muted">No products found.</p>}
      {result && result.items.length > 0 && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Name</th>
                  <th>Price</th>
                  <th>Counts toward</th>
                  <th>Status</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {result.items.map((p) => (
                  <tr key={p.id}>
                    <td className="mono">{p.code}</td>
                    <td>{p.name}</td>
                    <td>{money(p.standardPrice)}</td>
                    <td>
                      {[p.countsTowardQuotation && 'Quotation', p.countsTowardBanked && 'Banked']
                        .filter(Boolean)
                        .join(', ') || 'Neither'}
                    </td>
                    <td>
                      <span className={`badge ${p.active ? 'active' : 'suspended'}`}>
                        {p.active ? 'active' : 'inactive'}
                      </span>
                    </td>
                    <td>
                      {isAdmin && (
                        <button
                          type="button"
                          className="btn"
                          onClick={() => setPanel({ product: p })}
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

      {panel && (
        <ProductPanel
          key={panel.product?.id ?? 'new'}
          product={panel.product}
          onSaved={(saved, isNew) => {
            if (isNew) setPanel({ product: saved });
            setReload((n) => n + 1);
          }}
          onClose={() => setPanel(null)}
        />
      )}
    </>
  );
}
