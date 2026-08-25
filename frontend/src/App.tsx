import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { Home } from './pages/Home'
import { LoginPage } from './pages/LoginPage'
import { RecoveryDemoPage } from './pages/RecoveryDemoPage'
import { TransactionsPage } from './pages/TransactionsPage'
import { TransactionDetailPage } from './pages/TransactionDetailPage'
import { RequireAuth } from './components/RequireAuth'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/demo/recovery"
          element={
            <RequireAuth>
              <RecoveryDemoPage />
            </RequireAuth>
          }
        />
        <Route
          path="/transactions"
          element={
            <RequireAuth>
              <TransactionsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/transactions/:id"
          element={
            <RequireAuth>
              <TransactionDetailPage />
            </RequireAuth>
          }
        />
      </Routes>
    </BrowserRouter>
  )
}

export default App
