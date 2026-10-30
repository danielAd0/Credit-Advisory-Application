export default function ComingSoon({ title, notes }: { title: string; notes: string[] }) {
  return (
    <div className="panel">
      <h1>{title}</h1>
      <p className="muted">This screen is not built yet. Planned from the specification:</p>
      <ul>
        {notes.map((n) => (
          <li key={n}>{n}</li>
        ))}
      </ul>
    </div>
  );
}
