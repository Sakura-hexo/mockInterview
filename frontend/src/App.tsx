import { ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { getToken } from './api/http';
import LoginPage from './pages/LoginPage';
import './App.css';

// 路由守卫：未存 token 一律跳 /login（页面方案 3.1）
function RequireAuth({ children }: { children: ReactNode }) {
  if (!getToken()) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <RequireAuth>
              {/* 历史会话首页 F2 实现（页面方案 9 开发步骤） */}
              <div className="home-placeholder">历史会话首页（F2 实现）</div>
            </RequireAuth>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
