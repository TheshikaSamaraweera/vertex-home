import { useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { LoginPage } from './auth/LoginPage';
import { SignupPage } from './auth/SignupPage';
import { AppShell } from './components/AppShell';
import { Spinner } from './components/ui';
import { DashboardPage } from './pages/DashboardPage';
import { ItemsPage } from './pages/ItemsPage';
import { ItemSetsPage } from './pages/ItemSetsPage';
import { StockPage } from './pages/StockPage';
import { StoresPage } from './pages/StoresPage';
import { StoreDetailPage } from './pages/StoreDetailPage';
import { ReceivingPage } from './pages/ReceivingPage';
import { PurchaseOrdersPage } from './pages/PurchaseOrdersPage';
import { SuppliersPage } from './pages/SuppliersPage';
import { ReportsPage } from './pages/ReportsPage';
import { CustomerAnalyticsPage } from './pages/CustomerAnalyticsPage';
import { UsersPage } from './pages/UsersPage';
import { ReviewQueuePage } from './pages/ReviewQueuePage';
import { RegisterBusinessPage } from './pages/RegisterBusinessPage';
import { HierarchyPage } from './pages/HierarchyPage';
import { DistributorsPage, DistributorProfilePage } from './pages/DistributorsPage';
import { ReferralCardsPrintPage } from './pages/ReferralCardsPrintPage';
import { RewardsPage } from './pages/RewardsPage';
import { PortalLoginPage, PortalSignupPage } from './portal/PortalAuth';
import { PortalShell, RequireActive } from './portal/PortalShell';
import {
  PortalDashboard,
  PortalDetails,
  PortalReferrals,
  PortalStages,
} from './portal/PortalPages';
import { PortalRegistration } from './portal/PortalRegistration';

/**
 * Two applications, one bundle.
 *
 * `/portal/**` is the distributor's; everything else is staff. They share a session mechanism and a
 * design system and nothing else — different front doors, different navigation, different idea of
 * what "home" means. Which one an authenticated person gets is decided by the role they hold, not
 * by the address they typed, so a distributor who bookmarks a staff URL still lands in the portal.
 */
export function App() {
  const { user, loading, hasRole } = useAuth();

  // There used to be a third case here: /verify-email?token=… arriving on a browser with no
  // session, which had to reach the signup flow before any route guard ran. Accounts are usable
  // the moment they are created now, so nothing issues such a link and the path falls through to
  // sign-in like any other unknown one.
  const [showSignup, setShowSignup] = useState(false);

  // Waiting for the initial /auth/me. Rendering a login page here would flash it on every refresh
  // for anyone already signed in.
  if (loading) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-ground">
        <Spinner label="Restoring session…" />
      </div>
    );
  }

  // ---- nobody signed in -------------------------------------------------------------------
  if (!user) {
    return (
      <Routes>
        <Route path="/portal/signup" element={<PortalSignupPage />} />
        <Route path="/portal/*" element={<PortalLoginPage />} />
        <Route
          path="*"
          element={
            showSignup ? (
              <SignupPage onDone={() => setShowSignup(false)} />
            ) : (
              <LoginPage onSignup={() => setShowSignup(true)} />
            )
          }
        />
      </Routes>
    );
  }

  // ---- a distributor ----------------------------------------------------------------------
  //
  // Checked before the staff routes, and it wins: somebody holding only DISTRIBUTOR has no
  // business on a staff screen, and sending them to one they cannot use would be a worse
  // experience than the redirect. A staff account that also holds DISTRIBUTOR — which happens if
  // an administrator registers a business — is treated as staff, because the wider role decides.
  const distributorOnly = hasRole('DISTRIBUTOR') && !hasRole('SUPPORT_AGENT');

  if (distributorOnly) {
    return (
      <Routes>
        <Route element={<PortalShell />}>
          <Route path="/portal" element={<PortalDashboard />} />
          <Route
            path="/portal/details"
            element={
              <RequireActive>
                <PortalDetails />
              </RequireActive>
            }
          />
          <Route
            path="/portal/stages"
            element={
              <RequireActive>
                <PortalStages />
              </RequireActive>
            }
          />
          <Route
            path="/portal/referrals"
            element={
              <RequireActive>
                <PortalReferrals />
              </RequireActive>
            }
          />
          <Route path="/portal/registration" element={<PortalRegistration />} />
        </Route>
        <Route path="*" element={<Navigate to="/portal" replace />} />
      </Routes>
    );
  }

  // ---- staff ------------------------------------------------------------------------------
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/items" element={<ItemsPage />} />
        <Route path="/item-sets" element={<ItemSetsPage />} />
        <Route path="/stock" element={<StockPage />} />
        <Route path="/suppliers" element={<SuppliersPage />} />
        <Route path="/purchase-orders" element={<PurchaseOrdersPage />} />
        <Route path="/receiving" element={<ReceivingPage />} />
        <Route path="/stores" element={<StoresPage />} />
        <Route path="/stores/:id" element={<StoreDetailPage />} />
        <Route path="/reports" element={<ReportsPage />} />
        <Route path="/analytics" element={<CustomerAnalyticsPage />} />
        <Route path="/registrations" element={<ReviewQueuePage />} />
        <Route path="/distributors" element={<DistributorsPage />} />
        <Route path="/distributors/:id" element={<DistributorProfilePage />} />
        <Route path="/hierarchy" element={<HierarchyPage />} />
        <Route path="/rewards" element={<RewardsPage />} />
        <Route path="/referral-cards/:batchId" element={<ReferralCardsPrintPage />} />
        <Route path="/my-registration" element={<RegisterBusinessPage />} />
        <Route path="/users" element={<UsersPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
