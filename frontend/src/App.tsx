import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { Home } from './pages/Home'
import { RecoveryDemoPage } from './pages/RecoveryDemoPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/demo/recovery" element={<RecoveryDemoPage />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
