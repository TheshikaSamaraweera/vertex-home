import { useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { LoginPage } from './auth/LoginPage';
import { OfficerSignupPage } from './auth/OfficerSignupPage';
import { SignupPage } from './auth/SignupPage';
import { ForgotPasswordPage } from './auth/ForgotPasswordPage';
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
import { RewardTrackingPage } from './pages/RewardTrackingPage';
import { PortalLoginPage, PortalSignupPage } from './portal/PortalAuth';
import { PortalShell, RequireActive } from './portal/PortalShell';
import { PortalOffers } from './portal/PortalOffers';
import {
  PortalDashboard,
  PortalDetails,
  PortalReferrals,
  PortalStages,
} from './portal/PortalPages';
import { PortalItemPacks } from './portal/PortalItemPacks';
import { PortalRegistration } from './portal/PortalRegistration';
import { NotificationsPage } from './pages/NotificationsPage';
import { AnnouncementsPage } from './pages/AnnouncementsPage';
import { MyAccountPage } from './pages/MyAccountPage';
import { CostAnalysisPage } from './pages/CostAnalysisPage';
import { MarketingOfficersPage } from './pages/MarketingOfficersPage';
import { OfficerPortalPage } from './pages/OfficerPortalPage';

/**
 * Two applications, one bundle.
 *
 * `/portal/**` is the distributor's; everything else is staff. They share a session mechanism and a
 * design system and nothing else — different front doors, different navigation, different idea of
 * what "home" means. Which one an authenticated person gets is decided by the role they hold, not
 * by the address they typed, so a distributor who bookmarks a staff URL still lands in the portal.
 */
/** Back to the sign-in page from a route that has no session and no history worth keeping. */
function navigateToSignIn() {
  window.location.href = '/';
}

export function App() {
  const { user, loading, hasRole } = useAuth();

  // There used to be a third case here: /verify-email?token=… arriving on a browser with no
  // session, which had to reach the signup flow before any route guard ran. Accounts are usable
  // the moment they are created now, so nothing issues such a link and the path falls through to
  // sign-in like any other unknown one.
  const [showSignup, setShowSignup] = useState(false);
  const [showForgot, setShowForgot] = useState(false);

  // Waiting for the initial /auth/me. Rendering a login page here would flash it on every refresh
  // for anyone already signed in.
  if (loading) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-ground">
        <Spinner label="Restoring session…" />
      </div>
    );
  }

  // ---- signed in on a password somebody else chose -------------------------------------------
  //
  // Checked before any routing. An administrator issued this password, wrote it on a slip and may
  // have read it out across a counter; it must not become the password the account keeps. Letting
  // them past this screen and merely nagging would mean most people never change it.
  if (user?.mustChangePassword) {
    // No shell and no navigation, because there is nowhere else to go. A page with a sidebar full
    // of links that all lead back here would be a worse way of saying the same thing.
    return (
      <div className="mx-auto min-h-dvh max-w-3xl px-5 py-10">
        <MyAccountPage />
      </div>
    );
  }

  // ---- nobody signed in -------------------------------------------------------------------
  if (!user) {
    return (
      <Routes>
        <Route path="/portal/signup" element={<PortalSignupPage />} />
        {/* The officer front door. Its own route, because an applicant arrives here from a link
            they were sent rather than by clicking through the customer login. */}
        <Route path="/officer/signup" element={<OfficerSignupPage />} />
        <Route path="/portal/*" element={<PortalLoginPage />} />
        {/* Its own route rather than another flag on the catch-all: a reset link arrives as a URL
            with a token in it, and a URL has to survive being opened in a browser with no
            session and no memory of which screen was showing. */}
        <Route
          path="/reset-password"
          element={<ForgotPasswordPage onDone={() => navigateToSignIn()} />}
        />
        <Route
          path="*"
          element={
            showForgot ? (
              <ForgotPasswordPage onDone={() => setShowForgot(false)} />
            ) : showSignup ? (
              <SignupPage onDone={() => setShowSignup(false)} />
            ) : (
              <LoginPage
                onSignup={() => setShowSignup(true)}
                onForgotPassword={() => setShowForgot(true)}
              />
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

  // An officer who is nothing else lands on their own screen rather than a staff dashboard full
  // of tools they cannot use. Checked after distributorOnly, so somebody who is both a customer
  // and an officer stays in the portal they registered through.
  const officerOnly =
    hasRole('MARKETING_OFFICER') && !hasRole('SUPPORT_AGENT') && !hasRole('DISTRIBUTOR');

  if (officerOnly) {
    return (
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/officer" element={<OfficerPortalPage />} />
          <Route path="/my-account" element={<MyAccountPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="*" element={<Navigate to="/officer" replace />} />
        </Route>
      </Routes>
    );
  }

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
          {/* Not behind RequireActive: customers browse the packs before choosing one. */}
          <Route path="/portal/item-packs" element={<PortalItemPacks />} />
          {/* Open to every customer; the server filters each post by its audience. */}
          <Route path="/portal/offers" element={<PortalOffers />} />
          <Route path="/portal/notifications" element={<NotificationsPage />} />
          <Route path="/portal/account" element={<MyAccountPage />} />
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
        <Route path="/reward-tracking" element={<RewardTrackingPage />} />
        <Route path="/referral-cards/:batchId" element={<ReferralCardsPrintPage />} />
        <Route path="/my-registration" element={<RegisterBusinessPage />} />
        <Route path="/users" element={<UsersPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/announcements" element={<AnnouncementsPage />} />
        <Route path="/my-account" element={<MyAccountPage />} />
        <Route path="/cost-analysis" element={<CostAnalysisPage />} />
        <Route path="/marketing-officers" element={<MarketingOfficersPage />} />
        <Route path="/officer" element={<OfficerPortalPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
