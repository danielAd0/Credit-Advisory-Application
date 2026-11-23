import type { Form } from './productForm';

/** The product form fields, shared by the add page and the edit panel. */
export default function ProductFields({
  v,
  onChange,
  codeLocked,
}: {
  v: Form;
  onChange: (v: Form) => void;
  /** Editing: the code never changes. */
  codeLocked: boolean;
}) {
  return (
    <>
      <div className="ac-field">
        <label htmlFor="pr-code">Code</label>
        <input
          id="pr-code"
          value={v.code}
          disabled={codeLocked}
          onChange={(e) => onChange({ ...v, code: e.target.value })}
          autoComplete="off"
        />
        <p className="ac-hint">
          {codeLocked
            ? 'The code never changes.'
            : 'Saved in capitals, for example "funeral cover" becomes FUNERAL_COVER.'}
        </p>
      </div>
      <div className="ac-field">
        <label htmlFor="pr-name">Name</label>
        <input
          id="pr-name"
          value={v.name}
          onChange={(e) => onChange({ ...v, name: e.target.value })}
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
            onChange={(e) => onChange({ ...v, price: e.target.value })}
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
            onChange={(e) => onChange({ ...v, order: e.target.value })}
            autoComplete="off"
          />
        </div>
      </div>
      <p>
        <label>
          <input
            type="checkbox"
            checked={v.quotation}
            onChange={(e) => onChange({ ...v, quotation: e.target.checked })}
          />{' '}
          Counts toward quotation
        </label>
        <br />
        <label>
          <input
            type="checkbox"
            checked={v.banked}
            onChange={(e) => onChange({ ...v, banked: e.target.checked })}
          />{' '}
          Counts toward banked
        </label>
      </p>
    </>
  );
}
