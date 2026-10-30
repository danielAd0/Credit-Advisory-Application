import { BrowserRouter , Route , Routes } from "react-router-dom";
import { AuthProvider , useAuth } from "./auth/AuthContext";
import RequireAuth  from "./auth/RequireAuth";
import LoginPage from "./pages/LoginPage";

function Home(){
  const {session , signOut} = useAuth();
  return (
      <main className="center-msg">
          <h1>You are signed in</h1>
          <p>Welcome, {session?.username}</p>
          <button className="btn" onClick={() => void signOut()}>Sign out</button>
      </main>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="login" element={<LoginPage />} />
          <Route element={<RequireAuth />}>
            <Route index element={<Home />} />
            <Route path="*" element={<p className="center-msg">Page not found</p>} />
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}




