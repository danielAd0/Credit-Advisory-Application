import { useState } from 'react';
import { generatePassword } from '../api/passwords';

interface Props {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
}

export default function TempPassword({ id, label, value, onChange, error }: Props) {
  const [show, setShow] = useState(false);

  return (
    <div className="ac-field">
      <label htmlFor={id}>{label}</label>
      <div className="tp-row">
        <input
          id={id}
          type={show ? 'text' : 'password'}
          autoComplete="new-password"
          spellCheck={false}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={`${id}-hint`}
        />
        <button
          type="button"
          className="btn"
          onClick={() => {
            onChange(generatePassword());
            setShow(true);
          }}
        >
          Generate
        </button>
        <button type="button" className="btn" onClick={() => setShow((s) => !s)}>
          {show ? 'Hide' : 'Show'}
        </button>
      </div>
      <p id={`${id}-hint`} className={error ? 'ac-err' : 'ac-hint'}>
        {error ??
          'At least 12 characters. Give it to the consultant in person; they must change it.'}
      </p>
    </div>
  );
}
