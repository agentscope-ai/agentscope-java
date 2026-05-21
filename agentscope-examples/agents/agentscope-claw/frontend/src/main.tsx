import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from './pages/LoginPage';
import ChatPage from './pages/ChatPage';
import SessionsPage from './pages/SessionsPage';
import UsagePage from './pages/UsagePage';
import AppearancePage from './pages/AppearancePage';
import SkillsPage from './pages/SkillsPage';
import WorkspacePage from './pages/WorkspacePage';
import ProfilePage from './pages/ProfilePage';
import UserBindingsPage from './pages/UserBindingsPage';

function getToken(): string | null {
  return localStorage.getItem('claw_token');
}

function PrivateRoute({ children }: { children: React.ReactElement }) {
  return getToken() ? children : <Navigate to="/login" replace />;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/chat" element={<PrivateRoute><ChatPage /></PrivateRoute>} />
        <Route path="/sessions" element={<PrivateRoute><SessionsPage /></PrivateRoute>} />
        <Route path="/usage" element={<PrivateRoute><UsagePage /></PrivateRoute>} />
        <Route path="/appearance" element={<PrivateRoute><AppearancePage /></PrivateRoute>} />
        <Route path="/skills" element={<PrivateRoute><SkillsPage /></PrivateRoute>} />
        <Route path="/workspace" element={<PrivateRoute><WorkspacePage /></PrivateRoute>} />
        <Route path="/profile" element={<PrivateRoute><ProfilePage /></PrivateRoute>} />
        <Route path="/bindings" element={<PrivateRoute><UserBindingsPage /></PrivateRoute>} />
        <Route path="*" element={<Navigate to="/chat" replace />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
