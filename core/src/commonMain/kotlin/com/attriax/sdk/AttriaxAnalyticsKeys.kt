package com.attriax.sdk

/**
 * Reserved analytics event names used by the standardized tracking helpers.
 * Mirrors the Flutter reference `AttriaxAnalyticsEventKeys`
 * (`attriax_analytics_keys.dart`). Keep these in sync across SDKs so dashboard
 * funnels, SKAN rules, and revenue rollups agree on names.
 */
object AttriaxAnalyticsEventKeys {
    const val SIGN_UP = "sign_up"
    const val LOGIN = "login"
    const val TUTORIAL_BEGIN = "tutorial_begin"
    const val TUTORIAL_COMPLETE = "tutorial_complete"
    const val LEVEL_START = "level_start"
    const val LEVEL_COMPLETE = "level_complete"
    const val LEVEL_UP = "level_up"
    const val ADD_PAYMENT_INFO = "add_payment_info"
    const val ADD_TO_CART = "add_to_cart"
    const val CHECKOUT_STARTED = "checkout_started"
    const val PURCHASE = "purchase"
    const val REFUND = "refund"
    const val SUBSCRIPTION_STARTED = "subscription_started"
    const val SUBSCRIPTION_RENEWED = "subscription_renewed"
    const val TRIAL_STARTED = "trial_started"
    const val AD_REQUEST = "ad_request"
    const val AD_LOAD = "ad_load"
    const val AD_LOAD_FAILED = "ad_load_failed"
    const val AD_SHOW = "ad_show"
    const val AD_SHOW_FAILED = "ad_show_failed"
    const val AD_IMPRESSION = "ad_impression"
    const val AD_CLICK = "ad_click"
    const val AD_DISMISS = "ad_dismiss"
    const val AD_REWARD = "ad_reward"
    const val AD_REVENUE = "ad_revenue"
    const val PAGE_VIEW = "page_view"
}

/**
 * Reserved analytics payload keys used by the standardized tracking helpers.
 * Mirrors the Flutter reference
 * `AttriaxAnalyticsParamKeys`.
 */
object AttriaxAnalyticsParamKeys {
    const val REVENUE = "revenue"
    const val CURRENCY = "currency"
    const val REVENUE_IN_MICROS = "revenueInMicros"
    const val REVENUE_TYPE = "revenueType"
    const val PURCHASE_TYPE = "purchaseType"
    const val METHOD = "method"
    const val PAYMENT_TYPE = "paymentType"
    const val PRODUCT_ID = "productId"
    const val TRANSACTION_ID = "transactionId"
    const val ORIGINAL_TRANSACTION_ID = "originalTransactionId"
    const val VALIDATION_PROVIDER = "validationProvider"
    const val VALIDATION_ENVIRONMENT = "validationEnvironment"
    const val PURCHASE_TOKEN = "purchaseToken"
    const val RECEIPT_DATA = "receiptData"
    const val SIGNED_PAYLOAD = "signedPayload"
    const val RECEIPT_SIGNATURE = "receiptSignature"
    const val IS_RENEWAL = "isRenewal"
    const val QUANTITY = "quantity"
    const val STORE = "store"
    const val PACKAGE_NAME = "packageName"
    const val VOIDED = "voided"
    const val TEST = "test"
    const val VALIDATION_ID = "validationId"
    const val REASON = "reason"
    const val AD_NETWORK = "adNetwork"
    const val MEDIATION_NETWORK = "mediationNetwork"
    const val AD_UNIT_ID = "adUnitId"
    const val AD_PLACEMENT = "adPlacement"
    const val AD_FORMAT = "adFormat"
    const val AD_TYPE = "adType"
    const val FAILURE_REASON = "failureReason"
    const val LOAD_LATENCY_MS = "loadLatencyMs"
    const val REWARD_TYPE = "rewardType"
    const val REWARD_AMOUNT = "rewardAmount"
    const val PAGE_NAME = "pageName"
    const val PAGE_CLASS = "pageClass"
    const val PAGE_TITLE = "pageTitle"
    const val PREVIOUS_PAGE_NAME = "previousPageName"
    const val SOURCE = "source"
    const val LEVEL = "level"
    const val VALUE = "value"
}

/**
 * Canonical ad-lifecycle events tracked by [AttriaxTracking.recordAdEvent].
 * Mirrors the Flutter reference `AttriaxAdEventType`.
 */
enum class AttriaxAdEventType(val eventName: String) {
    REQUEST(AttriaxAnalyticsEventKeys.AD_REQUEST),
    LOAD(AttriaxAnalyticsEventKeys.AD_LOAD),
    LOAD_FAILED(AttriaxAnalyticsEventKeys.AD_LOAD_FAILED),
    SHOW(AttriaxAnalyticsEventKeys.AD_SHOW),
    SHOW_FAILED(AttriaxAnalyticsEventKeys.AD_SHOW_FAILED),
    IMPRESSION(AttriaxAnalyticsEventKeys.AD_IMPRESSION),
    CLICK(AttriaxAnalyticsEventKeys.AD_CLICK),
    DISMISS(AttriaxAnalyticsEventKeys.AD_DISMISS),
    REWARD(AttriaxAnalyticsEventKeys.AD_REWARD),
}

/**
 * Push-notification lifecycle stages attributed by [AttriaxTracking].
 * Wire values match the api `NotificationEventType` enum.
 */
enum class AttriaxNotificationEventType(val wireValue: String) {
    RECEIVED("received"),
    OPENED("opened"),
    DISMISSED("dismissed"),
}

/**
 * Delivery channel a push notification arrived through. Wire values match the
 * api `NotificationEventSource` enum. Inferred from the raw payload when omitted
 * (`aps` → apns; `google.`/`gcm.` → fcm).
 */
enum class AttriaxNotificationEventSource(val wireValue: String) {
    FCM("fcm"),
    APNS("apns"),
    OTHER("other"),
}
