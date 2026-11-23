import type { Form } from './employerForm';

/** The employer form fields, shared by the add page and the edit panel. */
export default function EmployerFields({ v, onChange }: { v: Form; onChange: (v: Form) => void }) {
  return (
    <>
      <div className="ac-field">
        <label htmlFor="em-name">Employer name</label>
        <input
          id="em-name"
          value={v.name}
          onChange={(e) => onChange({ ...v, name: e.target.value })}
          autoComplete="off"
        />
        <p className="ac-hint">Names must be unique, ignoring capitals and extra spaces.</p>
      </div>
      <div className="ac-row">
        <div className="ac-field">
          <label htmlFor="em-company">Company</label>
          <input
            id="em-company"
            value={v.company}
            onChange={(e) => onChange({ ...v, company: e.target.value })}
            autoComplete="off"
          />
        </div>
        <div className="ac-field">
          <label htmlFor="em-type">Employer type</label>
          <input
            id="em-type"
            value={v.employerType}
            onChange={(e) => onChange({ ...v, employerType: e.target.value })}
            autoComplete="off"
          />
        </div>
      </div>
    </>
  );
}
