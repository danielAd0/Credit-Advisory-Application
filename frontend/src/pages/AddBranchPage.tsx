import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { catalogue, detailOf, type ProductQuotation } from '../api';
import './addclient.css';
import './consultants.css';

export default function AddBranchPage() {
  const [name, setName] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [created, setCreated] = useState<ProductQuotation | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (saving) return;
    setFailure(null);
    if (!name.trim()) return setFailure('Enter the branch name.');
    setSaving(true);
    try {
      setCreated(await catalogue.createProductQuotation({ name: name.trim() }));
      setName('');
    } catch (err) {
      setFailure(detailOf(err) ?? 'Could not add the branch. Try again.');
    } finally {
      setSaving(false);
    }
  }

  if (created) {
    return (
      <div className="ac">
        <h1>Branch added</h1>
        <div className="ac-card" role="status">
          <p>
            <strong>{created.name}</strong> can now be chosen on a client.
          </p>
          <div className="ac-actions">
            <button type="button" className="btn primary" onClick={() => setCreated(null)}>
              Add another branch
            </button>
            <Link className="btn" to="/branches">
              Back to branches
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="ac">
      <h1>Add branch</h1>
      <p className="muted">Adds a branch to the list consultants choose from on a client.</p>
      <form className="ac-card" onSubmit={submit} noValidate>
        {failure && (
          <p className="alert error" role="alert">
            {failure}
          </p>
        )}
        <div className="ac-field">
          <label htmlFor="pq-name">Name</label>
          <input
            id="pq-name"
            value={name}
            maxLength={100}
            onChange={(e) => setName(e.target.value)}
            autoComplete="off"
          />
          <p className="ac-hint">Names must be unique, ignoring capitals and extra spaces.</p>
        </div>
        <div className="ac-actions">
          <button type="submit" className="btn primary" disabled={saving}>
            {saving ? 'Saving...' : 'Add branch'}
          </button>
          <Link className="btn" to="/branches">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  );
}
