import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { catalogue, detailOf, type Employer } from '../api';
import EmployerFields from './EmployerFields';
import { EMPTY, employerFields, type Form } from './employerForm';
import './addclient.css';
import './consultants.css';

export default function AddEmployerPage() {
  const [v, setV] = useState<Form>(EMPTY);
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [created, setCreated] = useState<Employer | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (saving) return;
    setFailure(null);
    if (!v.name.trim()) return setFailure('Enter the employer name.');
    setSaving(true);
    try {
      setCreated(await catalogue.createEmployer(employerFields(v)));
      setV(EMPTY);
    } catch (err) {
      setFailure(detailOf(err) ?? 'Could not add the employer. Try again.');
    } finally {
      setSaving(false);
    }
  }

  if (created) {
    return (
      <div className="ac">
        <h1>Employer added</h1>
        <div className="ac-card" role="status">
          <p>
            <strong>{created.name}</strong> is now in the employer schedule and can be chosen on a
            client.
          </p>
          <div className="ac-actions">
            <button type="button" className="btn primary" onClick={() => setCreated(null)}>
              Add another employer
            </button>
            <Link className="btn" to="/employers">
              Back to employers
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="ac">
      <h1>Add employer</h1>
      <p className="muted">For employers the weekly import does not bring in.</p>
      <form className="ac-card" onSubmit={submit} noValidate>
        {failure && (
          <p className="alert error" role="alert">
            {failure}
          </p>
        )}
        <EmployerFields v={v} onChange={setV} />
        <div className="ac-actions">
          <button type="submit" className="btn primary" disabled={saving}>
            {saving ? 'Saving...' : 'Add employer'}
          </button>
          <Link className="btn" to="/employers">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  );
}
