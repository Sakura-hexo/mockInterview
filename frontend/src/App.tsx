import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { getToken } from './api/http';
import Sidebar from './components/Sidebar';
import LoginPage from './pages/LoginPage';
import HistoryPage from './pages/HistoryPage';
import NewInterviewPage from './pages/NewInterviewPage';
import InterviewPage from './pages/InterviewPage';
import ReportPage from './pages/ReportPage';
import './App.css';

// 路由守卫：未存 token 一律跳 /login（页面方案 3.1）
function RequireAuth() {
  if (!getToken()) return <Navigate to="/login" replace />;
  return <Outlet />;
}

// 登录外的所有页面共享：左栏 Sidebar + 主区（页面方案 3.2 骨架）
function AppLayout() {
  return (
    <div className="app">
      <Sidebar />
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireAuth />}>
          <Route element={<AppLayout />}>
            <Route path="/" element={<HistoryPage />} />
            <Route path="/new" element={<NewInterviewPage />} />
            <Route path="/interview/:id" element={<InterviewPage />} />
            <Route path="/report/:id" element={<ReportPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
