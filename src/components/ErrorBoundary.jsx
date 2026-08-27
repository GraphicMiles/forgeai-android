import { Component } from 'react';
import { recordError } from '../utils/errorLog.js';

export default class ErrorBoundary extends Component {
  state = { error: null };
  static getDerivedStateFromError(error) { return { error }; }
  componentDidCatch(error, info) { recordError(error, 'react-boundary'); console.error('Luna UI error', error, info); }
  reset = () => { this.setState({ error: null }); };
  reload = () => { window.location.reload(); };
  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="app">
        <div className="grow">
          <div className="top"><span className="title">Something broke</span></div>
          <div className="note">
            <i className="fa-solid fa-triangle-exclamation" aria-hidden="true" />
            <span>{this.state.error.message || 'The app hit an unexpected error. Nothing on your device was changed.'}</span>
          </div>
          <button type="button" className="btn soft wide" onClick={this.reset}>Try again</button>
          <button type="button" className="btn wide" onClick={this.reload}>Reload Luna</button>
        </div>
      </div>
    );
  }
}
