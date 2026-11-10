import { Link } from 'react-router-dom';
import DashboardCharts from '../components/DashboardCharts';
import './dashboard.css';

export default function ChartsPage() {
  return (
    <div className="dash">
      <header className="dash-head">
        <div>
          <p className="eyebrow">Performance overview</p>
          <h1>Performance charts</h1>
          <p className="muted">
            Weekly, daily, consultant and product figures for the latest week.
          </p>
        </div>
        <Link className="btn" to="/">
          Back to overview
        </Link>
      </header>
      <DashboardCharts />
    </div>
  );
}
