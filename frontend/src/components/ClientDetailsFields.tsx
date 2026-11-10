import { useEffect, useState, type ChangeEvent } from 'react';
import { catalogue, type ClientSummary, type NamedRef, type PageResponse } from '../api';
import { FILE_REFERENCE_MAX, type DetailsErrors, type DetailsValues } from './clientDetails';

/**
 * The client fields the Add and Edit client forms share: phones, employer,
 * product quotation, payment method, File number and File ID. Consultants fill
 * these in, so everything that can be a dropdown is one, and the file fields
 * only take digits.
 */

/** Every page of a list, for a dropdown. */
async function everyPage<T>(fetch: (page: number) => Promise<PageResponse<T>>): Promise<T[]> {
  const first = await fetch(0);
  const rest = await Promise.all(
    Array.from({ length: Math.max(0, first.totalPages - 1) }, (_, i) => fetch(i + 1)),
  );
  return [first, ...rest].flatMap((p) => p.items);
}

interface Lists {
  employers: NamedRef[];
  quotations: NamedRef[];
  failed: boolean;
}

/** The active employers and product quotations to choose from. */
function useActiveLists(): Lists | null {
  const [lists, setLists] = useState<Lists | null>(null);
  useEffect(() => {
    let current = true;
    Promise.all([
      everyPage((page) => catalogue.listEmployers({ active: true, page, size: 100 })),
      everyPage((page) => catalogue.listProductQuotations({ active: true, page, size: 200 })),
    ])
      .then(([employers, quotations]) => {
        if (current) setLists({ employers, quotations, failed: false });
      })
      .catch(() => {
        if (current) setLists({ employers: [], quotations: [], failed: true });
      });
    return () => {
      current = false;
    };
  }, []);
  return lists;
}

/**
 * The options for one dropdown. The client's current entry stays in the list
 * even if an admin has deactivated it since, so editing a client never changes
 * it by accident.
 */
function options(active: NamedRef[], current: NamedRef | null): NamedRef[] {
  if (!current || active.some((x) => x.id === current.id)) return active;
  return [...active, current].sort((a, b) => a.name.localeCompare(b.name));
}

interface Props {
  /** Prefix for the input ids, so the add and edit forms do not clash. */
  idPrefix: string;
  values: DetailsValues;
  errors: DetailsErrors;
  onChange: (key: keyof DetailsValues, value: string) => void;
  /** The client being edited, for entries that have been deactivated since. */
  current?: Pick<ClientSummary, 'employer' | 'productQuotation'>;
}

export default function ClientDetailsFields({
  idPrefix,
  values,
  errors,
  onChange,
  current,
}: Props) {
  const lists = useActiveLists();

  const set =
    (key: keyof DetailsValues) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      const raw = e.target.value;
      // Digits only: a pasted space, dash or letter cannot cause a rejection later.
      const value =
        key === 'fileNumber' || key === 'fileId'
          ? raw.replace(/\D/g, '').slice(0, FILE_REFERENCE_MAX)
          : raw;
      onChange(key, value);
    };

  const id = (key: keyof DetailsValues) => `${idPrefix}-${key}`;

  const text = (
    key: keyof DetailsValues,
    label: string,
    extra: { type?: string; inputMode?: 'numeric' | 'tel'; hint?: string } = {},
  ) => (
    <div className="ac-field">
      <label htmlFor={id(key)}>{label}</label>
      <input
        id={id(key)}
        type={extra.type ?? 'text'}
        inputMode={extra.inputMode}
        autoComplete="off"
        spellCheck={false}
        value={values[key]}
        onChange={set(key)}
        maxLength={key === 'fileNumber' || key === 'fileId' ? FILE_REFERENCE_MAX : 30}
        aria-invalid={errors[key] ? true : undefined}
        aria-describedby={
          errors[key] ? `${id(key)}-err` : extra.hint ? `${id(key)}-hint` : undefined
        }
      />
      {errors[key] ? (
        <p id={`${id(key)}-err`} className="ac-err">
          {errors[key]}
        </p>
      ) : extra.hint ? (
        <p id={`${id(key)}-hint`} className="ac-hint">
          {extra.hint}
        </p>
      ) : null}
    </div>
  );

  const dropdown = (
    key: 'employerId' | 'productQuotationId',
    label: string,
    choices: NamedRef[],
    none: string,
  ) => (
    <div className="ac-field">
      <label htmlFor={id(key)}>{label}</label>
      <select id={id(key)} value={values[key]} onChange={set(key)} disabled={!lists}>
        <option value="">{lists ? none : 'Loading...'}</option>
        {choices.map((x) => (
          <option key={x.id} value={x.id}>
            {x.active ? x.name : `${x.name} (deactivated)`}
          </option>
        ))}
      </select>
    </div>
  );

  const debitOrder = values.paymentMethod === 'DEBIT_ORDER';

  return (
    <>
      <div className="ac-row ac-row-3">
        {text('phone', 'Cellphone', { type: 'tel', inputMode: 'tel' })}
        {text('workPhone', 'Work telephone', { type: 'tel', inputMode: 'tel' })}
        {text('homePhone', 'Home telephone', { type: 'tel', inputMode: 'tel' })}
      </div>

      {lists?.failed && (
        <p className="alert error" role="alert">
          Could not load the employer and product quotation lists. Refresh the page to choose them.
        </p>
      )}
      <div className="ac-row">
        {dropdown(
          'employerId',
          'Client employer',
          options(lists?.employers ?? [], current?.employer ?? null),
          debitOrder ? 'None (debit order)' : 'None',
        )}
        <div className="ac-field">
          <label htmlFor={id('paymentMethod')}>How the client pays</label>
          <select
            id={id('paymentMethod')}
            value={values.paymentMethod}
            onChange={set('paymentMethod')}
          >
            <option value="">Through the employer</option>
            <option value="DEBIT_ORDER">Debit order</option>
          </select>
          {debitOrder && values.employerId && (
            <p className="ac-hint">
              Debit order clients usually have no employer. Check this is right.
            </p>
          )}
        </div>
      </div>
      {dropdown(
        'productQuotationId',
        'Product quotation',
        options(lists?.quotations ?? [], current?.productQuotation ?? null),
        'None',
      )}

      <div className="ac-row">
        {text('fileNumber', 'File number', {
          inputMode: 'numeric',
          hint: 'Digits only. Leading zeros are kept.',
        })}
        {text('fileId', 'File ID', {
          inputMode: 'numeric',
          hint: 'Digits only. Not the same as the File number.',
        })}
      </div>
    </>
  );
}
