import { Component } from 'react';
import { recordError } from '../utils/errorLog.js';
import './ErrorBoundary.css';

export default class ErrorBoundary extends Component {
  state = { error: null };
  static getDerivedStateFromError(error) { return { error }; }
  componentDidCatch(error, info) { recordError(error, 'react-boundary'); console.error('Luna UI error', error, info); }
  reset = () => { this.setState({ error: null }); };
  reload = () => { window.location.reload(); };
  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="error-boundary-screen">
        <div className="error-boundary-card">
          <h2>Something went wrong</h2>
          <p>{this.state.error.message || 'The app encountered an unexpected error.'}</p>
          <div className="error-boundary-actions">
            <button type="button" onClick={this.reset}>Try again</button>
            <button className="primary" type="button" onClick={this.reload}>Reload app</button>
          </div>
        </div>
      </div>
    );
  }
}
