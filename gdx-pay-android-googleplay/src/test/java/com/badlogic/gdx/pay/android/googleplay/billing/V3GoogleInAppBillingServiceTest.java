package com.badlogic.gdx.pay.android.googleplay.billing;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.vending.billing.IInAppBillingService;
import com.badlogic.gdx.backends.android.AndroidEventListener;
import com.badlogic.gdx.pay.Information;
import com.badlogic.gdx.pay.Offer;
import com.badlogic.gdx.pay.Transaction;
import com.badlogic.gdx.pay.GdxPayException;
import com.badlogic.gdx.pay.android.googleplay.billing.GoogleInAppBillingService.ConnectionListener;
import com.badlogic.gdx.pay.android.googleplay.billing.converter.PurchaseResponseActivityResultConverter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.badlogic.gdx.pay.android.googleplay.ResponseCode.BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE;
import static com.badlogic.gdx.pay.android.googleplay.billing.V3GoogleInAppBillingService.BILLING_API_VERSION;
import static com.badlogic.gdx.pay.android.googleplay.billing.V3GoogleInAppBillingService.DEFAULT_DEVELOPER_PAYLOAD;
import static com.badlogic.gdx.pay.android.googleplay.billing.V3GoogleInAppBillingService.PURCHASE_TYPE_IN_APP;
import static com.badlogic.gdx.pay.android.googleplay.billing.V3GoogleInAppBillingService.PURCHASE_TYPE_SUBSCRIPTION;
import static com.badlogic.gdx.pay.android.googleplay.billing.V3GoogleInAppBillingService.RETRY_PURCHASE_DELAY_IN_MS;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetBuyIntentResponseObjectMother.buyIntentResponseOk;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetPurchasesResponseObjectMother.purchasesResponseEmptyResponse;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetPurchasesResponseObjectMother.purchasesResponseOneTransactionFullEdition;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetPurchasesResponseObjectMother.purchasesResponseOneTransactionFullEditionSandboxOrder;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetSkuDetailsResponseBundleObjectMother.skuDetailsResponseResultNetworkError;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetSkuDetailsResponseBundleObjectMother.skuDetailsResponseResultOkProductFullEditionEntitlement;
import static com.badlogic.gdx.pay.android.googleplay.testdata.InformationObjectMother.informationFullEditionEntitlement;
import static com.badlogic.gdx.pay.android.googleplay.testdata.OfferObjectMother.offerFullEditionEntitlement;
import static com.badlogic.gdx.pay.android.googleplay.testdata.PurchaseRequestActivityResultObjectMother.activityResultPurchaseFullEditionSuccess;
import static com.badlogic.gdx.pay.android.googleplay.testdata.TestConstants.PACKAGE_NAME_GOOD;
import static com.badlogic.gdx.pay.android.googleplay.testdata.TransactionObjectMother.transactionFullEditionEuroGooglePlay;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class V3GoogleInAppBillingServiceTest {

    public static final int ACTIVITY_REQUEST_CODE = 1002;
    @Mock
    ApplicationProxy applicationProxy;

    @Captor
    ArgumentCaptor<ServiceConnection> serviceConnectionArgumentCaptor;

    @Captor
    ArgumentCaptor<AndroidEventListener> androidEventListenerArgumentCaptor;

    @Mock
    IInAppBillingService nativeInAppBillingService;

    @Mock
    ConnectionListener connectionListener;

    @Mock
    GoogleInAppBillingService.PurchaseRequestCallback purchaseRequestCallback;

    @Mock
    PurchaseResponseActivityResultConverter purchaseResponseActivityResultConverter;

    @Mock
    AsyncExecutor asyncExecutor;

    @Captor
    ArgumentCaptor<Runnable> runnableArgumentCaptor;

    private V3GoogleInAppBillingService v3InAppbillingService;

    @Rule
    public ExpectedException thrown = ExpectedException.none();
    private AndroidEventListener androidEventListener;

    @Before
    public void setUp() throws Exception {
        when(applicationProxy.getPackageName()).thenReturn(PACKAGE_NAME_GOOD);

        v3InAppbillingService = new V3GoogleInAppBillingService(applicationProxy, ACTIVITY_REQUEST_CODE, purchaseResponseActivityResultConverter, asyncExecutor) {
            @Override
            protected IInAppBillingService lookupByStubAsInterface(IBinder binder) {
                return nativeInAppBillingService;
            }
        };

        androidEventListener = captureAndroidEventListener();
    }

    @Test
    public void shouldNotCancelRealPurchaseWhenCancellingTestPurchases() throws Exception {
        activityBindAndConnect();

        whenGetPurchasesRequestReturn(purchasesResponseOneTransactionFullEdition());

        v3InAppbillingService.cancelTestPurchases();

        verify(nativeInAppBillingService).getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_IN_APP, null);
        verify(nativeInAppBillingService).getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_SUBSCRIPTION, null);

        verifyNoMoreInteractions(nativeInAppBillingService);
    }

    @Test
    public void shouldCancelTestPurchaseWhenCancellingTestPurchases() throws Exception {
        activityBindAndConnect();

        whenGetPurchasesRequestReturn(purchasesResponseOneTransactionFullEditionSandboxOrder());

        v3InAppbillingService.cancelTestPurchases();

        verify(nativeInAppBillingService).getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_IN_APP, null);

        verify(nativeInAppBillingService).consumePurchase(eq(BILLING_API_VERSION), eq(PACKAGE_NAME_GOOD), anyString());
    }

    @Test
    public void installShouldStartActivityIntent() throws Exception {

        whenActivityBindReturn(true);

        requestConnect();

        verify(applicationProxy).bindService(isA(Intent.class), isA(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));
    }

    @Test
    public void shouldCallObserverInstallErrorOnActivityBindFailure() throws Exception {
        whenActivityBindThrow(new SecurityException("Not allowed to bind to this service"));

        requestConnect();

        verify(connectionListener).disconnected(isA(GdxPayException.class));
    }

    @Test
    public void shouldCallConnectionListenerFailureWhenActivityBindReturnsFalse() throws Exception {
        whenActivityBindReturn(false);

        requestConnect();

        verify(connectionListener).disconnected(isA(GdxPayException.class));
    }

    @Test
    public void shouldCallConnectSuccessWhenConnectSucceeds() throws Exception {
        activityBindAndConnect();

        verify(connectionListener).connected();
    }

    @Test
    public void shouldReturnSkusWhenResponseIsOk() throws Exception {

        whenBillingServiceGetSkuDetailsReturn(skuDetailsResponseResultOkProductFullEditionEntitlement());

        activityBindAndConnect();

        Offer offer = offerFullEditionEntitlement();

        Map<String, Information> details = v3InAppbillingService.getProductsDetails(singletonList(offer.getIdentifier()), PURCHASE_TYPE_IN_APP);

        assertEquals(details, Collections.singletonMap(offer.getIdentifier(), informationFullEditionEntitlement()));
    }

    @Test
    public void shouldThrowExceptionWhenGetSkuDetailsResponseResultIsNetworkError() throws Exception {
        whenBillingServiceGetSkuDetailsReturn(skuDetailsResponseResultNetworkError());

        activityBindAndConnect();

        thrown.expect(GdxPayException.class);

        v3InAppbillingService.getProductsDetails(singletonList("TEST"), PURCHASE_TYPE_IN_APP);
    }

    @Test
    public void shouldThrowExceptionOnGetSkuDetailsWhenDisconnected() throws Exception {
        thrown.expect(GdxPayException.class);

        v3InAppbillingService.getProductsDetails(singletonList("TEST"), PURCHASE_TYPE_IN_APP);
    }

    @Test
    public void shouldStartSenderIntentForBuyIntentResponseOk() throws Exception {
        activityBindAndConnect();

        Offer offer = offerFullEditionEntitlement();

        whenGetBuyIntentForIdentifierReturn(offer.getIdentifier(), buyIntentResponseOk());

        v3InAppbillingService.startPurchaseRequest(offer.getIdentifier(), PURCHASE_TYPE_IN_APP, purchaseRequestCallback);

        verify(applicationProxy).startIntentSenderForResult(isA(IntentSender.class),
                eq(ACTIVITY_REQUEST_CODE), isA(Intent.class), eq(0), eq(0), eq(0));
    }

    @Test
    public void shoulReconnectAndRetryWhenGetBuyIntentFailsWithDeadObjectException() throws Exception {
        activityBindAndConnect();

        Offer offer = offerFullEditionEntitlement();

        whenGetBuyIntentForIdentifierThrow(offer.getIdentifier(), new DeadObjectException("Purchase service died."));

        v3InAppbillingService.startPurchaseRequest(offer.getIdentifier(), PURCHASE_TYPE_IN_APP, purchaseRequestCallback);

        verify(applicationProxy).unbindService(isA(ServiceConnection.class));

        verifyAndroidApplicationBindService(2);

        verify(asyncExecutor).executeAsync(isA(Runnable.class), eq(RETRY_PURCHASE_DELAY_IN_MS));
    }

    @Test
    public void shouldCallGdxPurchaseCallbackErrorAndReconnectWhenGetBuyIntentFailsWithDeadObjectException() throws Exception {
        activityBindAndConnect();

        Offer offer = offerFullEditionEntitlement();

        whenGetBuyIntentForIdentifierThrow(offer.getIdentifier(), new DeadObjectException("Purchase service died."));

        v3InAppbillingService.startPurchaseRequest(offer.getIdentifier(), PURCHASE_TYPE_IN_APP, purchaseRequestCallback);

        verifyAsyncExecutorWasCalledForRetryAndRetryRun();

        verify(purchaseRequestCallback).purchaseError(isA(GdxPayException.class));

        verify(applicationProxy).unbindService(isA(ServiceConnection.class));

        verifyAndroidApplicationBindService(2);
    }

    protected void verifyAsyncExecutorWasCalledForRetryAndRetryRun() {
        verify(asyncExecutor).executeAsync(runnableArgumentCaptor.capture(), isA(Long.class));
        runnableArgumentCaptor.getValue().run();
    }

    @Test
    public void shouldCallPurchaseCallbackErrorWhenPurcharseIntentSenderForResultFails() throws Exception {
        activityBindAndConnect();

        Offer offer = offerFullEditionEntitlement();

        whenGetBuyIntentForIdentifierReturn(offer.getIdentifier(), buyIntentResponseOk());

        doThrow(new IntentSender.SendIntentException("Intent cancelled")).when(applicationProxy)
                .startIntentSenderForResult(isA(IntentSender.class),
                        eq(ACTIVITY_REQUEST_CODE), isA(Intent.class), eq(0), eq(0), eq(0));
        v3InAppbillingService.startPurchaseRequest(offer.getIdentifier(), PURCHASE_TYPE_IN_APP, purchaseRequestCallback);

        verify(applicationProxy).startIntentSenderForResult(isA(IntentSender.class),
                eq(ACTIVITY_REQUEST_CODE), isA(Intent.class), eq(0), eq(0), eq(0));

        verify(purchaseRequestCallback).purchaseError(isA(GdxPayException.class));
    }

    @Test
    public void shouldCallPurchaseListenerOnActivityResultAfterSuccessfulPurchaseRequest() throws Exception {
        Offer offer = offerFullEditionEntitlement();

        bindConnectAndStartPurchaseRequest(offer);

        whenBillingServiceGetSkuDetailsReturn(skuDetailsResponseResultOkProductFullEditionEntitlement());

        when(purchaseResponseActivityResultConverter.convertToTransaction(isA(Intent.class)))
                .thenReturn(transactionFullEditionEuroGooglePlay());

        androidEventListener.onActivityResult(ACTIVITY_REQUEST_CODE, Activity.RESULT_OK, activityResultPurchaseFullEditionSuccess());

        verify(purchaseRequestCallback).purchaseSuccess(isA(Transaction.class));
    }

    @Test
    public void shouldCallPurchaseErrorIfConvertingIntentDataToTransactionFails() throws Exception {
        bindConnectAndStartPurchaseRequest(offerFullEditionEntitlement());

        when(purchaseResponseActivityResultConverter.convertToTransaction(isA(Intent.class)))
                .thenThrow(new GdxPayException("Exception parsing Json"));

        androidEventListener.onActivityResult(ACTIVITY_REQUEST_CODE, Activity.RESULT_OK, activityResultPurchaseFullEditionSuccess());

        verify(purchaseRequestCallback).purchaseError(isA(GdxPayException.class));
    }


    @Test
    public void shouldCallPurchaseErrorIfResultIsError() throws Exception {
        bindConnectAndStartPurchaseRequest(offerFullEditionEntitlement());

        androidEventListener.onActivityResult(ACTIVITY_REQUEST_CODE, BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE.getCode(), activityResultPurchaseFullEditionSuccess());

        verify(purchaseRequestCallback).purchaseError(isA(GdxPayException.class));

        verifyNoMoreInteractions(purchaseResponseActivityResultConverter);
    }

    @Test
    public void shouldCallPurchaseCanceledOnResultCodeZero() throws Exception {
        Offer offer = offerFullEditionEntitlement();

        bindConnectAndStartPurchaseRequest(offer);

        whenBillingServiceGetSkuDetailsReturn(skuDetailsResponseResultOkProductFullEditionEntitlement());

        androidEventListener.onActivityResult(ACTIVITY_REQUEST_CODE, Activity.RESULT_CANCELED, new Intent());

        verify(purchaseRequestCallback).purchaseCanceled();
    }

    @Test
    public void getPurchasesWithResultOkShouldReturnPurchaseTransactions() throws Exception {
        activityBindAndConnect();

        whenGetPurchasesRequestReturn(purchasesResponseOneTransactionFullEdition());

        List<Transaction> transactions = v3InAppbillingService.getPurchases();

        verify(nativeInAppBillingService).getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_IN_APP, null);

        assertEquals(1, transactions.size());

        assertEquals(offerFullEditionEntitlement().getIdentifier(), transactions.get(0).getIdentifier());
    }

    @Test
    public void getSubscriptionPurchasesWithResultOkShouldReturnPurchaseTransactions() throws Exception {
        activityBindAndConnect();

        whenGetPurchasesRequestReturn(purchasesResponseOneTransactionFullEdition());

        List<Transaction> transactions = v3InAppbillingService.getPurchases();

        verify(nativeInAppBillingService).getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_SUBSCRIPTION, null);

        assertEquals(1, transactions.size());

        assertEquals(offerFullEditionEntitlement().getIdentifier(), transactions.get(0).getIdentifier());
    }

    @Test
    public void shouldThrowGdxPayExceptionWhenGetPurchasesFails() throws Exception {
        activityBindAndConnect();

        thrown.expect(GdxPayException.class);

        whenGetPurchasesRequestThrow(new DeadObjectException("Disconnected"));

        v3InAppbillingService.getPurchases();
    }

    @Test
    public void onServiceDisconnectedShouldDisconnectService() throws Exception {
        ServiceConnection connection = bindAndFetchNewConnection();

        connection.onServiceConnected(null, null);

        assertTrue(v3InAppbillingService.isListeningForConnections());

        connection.onServiceDisconnected(null);

        assertFalse(v3InAppbillingService.isConnected());
    }

    @Test
    public void connectingTwiceInARowShouldBeBlocked() throws Exception {
        ServiceConnection connection = bindAndFetchNewConnection();

        connection.onServiceConnected(null, null);
        connection.onServiceConnected(null, null);

        verify(connectionListener, times(1)).connected();
    }

    @Test
    public void disconnectShouldDisconnectFromActivity() throws Exception {
        ServiceConnection connection = bindAndFetchNewConnection();

        connection.onServiceConnected(null, null);

        v3InAppbillingService.disconnect();

        verify(applicationProxy).unbindService(connection);

        assertFalse(v3InAppbillingService.isListeningForConnections());
        assertFalse(v3InAppbillingService.isConnected());
    }

    @Test
    public void calculatesDeltaCorrectly() throws Exception {
        int actualDelta= v3InAppbillingService.deltaInSeconds(10_001, 5_000);

        assertEquals(5, actualDelta);
    }

    @Test
    public void calculatesDeltaCorrectlyTwoTimestamps() throws Exception {
        int actualDelta= v3InAppbillingService.deltaInSeconds(System.currentTimeMillis() - 5_001);

        assertEquals(5, actualDelta);
    }

    @Test
    public void disposeShouldRemoveAndroidListener() throws Exception {
        v3InAppbillingService.dispose();

        verify(applicationProxy).removeAndroidEventListener(androidEventListener);
    }

    @Test
    public void disconnectShouldNotCrashWhenUnBindThrowsException() throws Exception {
        ServiceConnection serviceConnection = bindAndFetchNewConnection();

        doThrow(new IllegalArgumentException("Service not registered")).when(applicationProxy).unbindService(serviceConnection);

        v3InAppbillingService.disconnect();

        verify(applicationProxy).unbindService(serviceConnection);
    }

    private void whenGetPurchasesRequestThrow(Exception exception) {
        try {
            when(nativeInAppBillingService.getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_IN_APP, null))
                    .thenThrow(exception);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void whenGetPurchasesRequestReturn(Bundle response) {
        try {
            when(nativeInAppBillingService.getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_IN_APP, null)).thenReturn(response);
            when(nativeInAppBillingService.getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_SUBSCRIPTION, null)).thenReturn(purchasesResponseEmptyResponse());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void bindConnectAndStartPurchaseRequest(Offer offer) throws android.os.RemoteException {
        activityBindAndConnect();


        whenGetBuyIntentForIdentifierReturn(offer.getIdentifier(), buyIntentResponseOk());

        v3InAppbillingService.startPurchaseRequest(offer.getIdentifier(), PURCHASE_TYPE_IN_APP, purchaseRequestCallback);
    }

    private void whenGetBuyIntentForIdentifierReturn(String productId, Bundle buyIntentResponse) throws android.os.RemoteException {
        when(nativeInAppBillingService.getBuyIntent(BILLING_API_VERSION, PACKAGE_NAME_GOOD, productId,
                PURCHASE_TYPE_IN_APP, DEFAULT_DEVELOPER_PAYLOAD))
                .thenReturn(buyIntentResponse);
    }

    private void whenGetBuyIntentForIdentifierThrow(String productId, RemoteException exception) throws RemoteException {
        when(nativeInAppBillingService.getBuyIntent(BILLING_API_VERSION, PACKAGE_NAME_GOOD, productId,
                PURCHASE_TYPE_IN_APP, DEFAULT_DEVELOPER_PAYLOAD))
                .thenThrow(exception);
    }

    private void activityBindAndConnect() {
        ServiceConnection connection = bindAndFetchNewConnection();

        connection.onServiceConnected(null, null);
    }

    private void whenBillingServiceGetSkuDetailsReturn(Bundle skuDetailsResponse) throws android.os.RemoteException {
        when(nativeInAppBillingService.getSkuDetails(
                        eq(BILLING_API_VERSION),
                        isA(String.class),
                        eq(PURCHASE_TYPE_IN_APP),
                        isA(Bundle.class))
        ).thenReturn(skuDetailsResponse);
    }

    private ServiceConnection bindAndFetchNewConnection() {
        whenActivityBindReturn(true);

        requestConnect();

        verifyAndroidApplicationBindService(1);

        return serviceConnectionArgumentCaptor.getValue();
    }

    private void verifyAndroidApplicationBindService(int times) {
        verify(applicationProxy, times(times)).bindService(isA(Intent.class), serviceConnectionArgumentCaptor.capture(), eq(Context.BIND_AUTO_CREATE));
    }

    private void whenActivityBindThrow(SecurityException exception) {
        when(applicationProxy.bindService(isA(Intent.class), isA(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE)))
                .thenThrow(exception);
    }


    private void whenActivityBindReturn(boolean returnValue) {
        when(applicationProxy.bindService(isA(Intent.class), isA(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE))).thenReturn(returnValue);
    }

    private void requestConnect() {
        v3InAppbillingService.requestConnect(connectionListener);
    }

    private AndroidEventListener captureAndroidEventListener() {
        verify(applicationProxy).addAndroidEventListener(androidEventListenerArgumentCaptor.capture());
        return androidEventListenerArgumentCaptor.getValue();
    }
}