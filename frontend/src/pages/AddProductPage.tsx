import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { catalogue, detailOf, type Product } from '../api';
import ProductFields from './ProductFields';
import { EMPTY, productFields, type Form } from './productForm';
import './addclient.css';
import './consultants.css';

export default function AddProductPage() {
  const [v, setV] = useState<Form>(EMPTY);
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [created, setCreated] = useState<Product | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (saving) return;
    setFailure(null);
    if (!v.code.trim()) return setFailure('Enter a product code.');
    const checked = productFields(v);
    if (checked.error !== undefined) return setFailure(checked.error);
    setSaving(true);
    try {
      setCreated(await catalogue.createProduct({ ...checked.fields, code: v.code.trim() }));
      setV(EMPTY);
    } catch (err) {
      setFailure(detailOf(err) ?? 'Could not add the product. Try again.');
    } finally {
      setSaving(false);
    }
  }

  if (created) {
    return (
      <div className="ac">
        <h1>Product added</h1>
        <div className="ac-card" role="status">
          <p>
            <strong>{created.name}</strong> ({created.code}) is now in the product catalogue.
          </p>
          <div className="ac-actions">
            <button type="button" className="btn primary" onClick={() => setCreated(null)}>
              Add another product
            </button>
            <Link className="btn" to="/products">
              Back to products
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="ac">
      <h1>Add product</h1>
      <p className="muted">For products the weekly register does not have a column for.</p>
      <form className="ac-card" onSubmit={submit} noValidate>
        {failure && (
          <p className="alert error" role="alert">
            {failure}
          </p>
        )}
        <ProductFields v={v} onChange={setV} codeLocked={false} />
        <div className="ac-actions">
          <button type="submit" className="btn primary" disabled={saving}>
            {saving ? 'Saving...' : 'Add product'}
          </button>
          <Link className="btn" to="/products">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  );
}
