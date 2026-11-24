# Data safety answers: Gallerai 1.0.6

Play Console → **App content → Data safety**. These answers match what the app's code and libraries actually do in version 1.0.6.

## Overview questions
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** |
| Do you provide a way for users to request that their data is deleted? | **Yes**: users can email **mydjearth@gmail.com** (stated in the privacy policy, section 7). |

## Data types collected
Collected by **Firebase Analytics / Remote Config** and **Google Play services (ML Kit)**. Not shared with third parties: Google acts as your service provider.

| Category → type | Collected | Shared | Required or optional | Purposes |
|---|---|---|---|---|
| App activity → **App interactions** | Yes | No | Required | Analytics |
| App info and performance → **Diagnostics** | Yes | No | Required | Analytics, App functionality |
| Device or other IDs → **Device or other IDs** (Firebase app instance ID) | Yes | No | Required | Analytics |

**Do NOT declare:**
- **App settings** (theme, accent colour, view options). They are stored on the device only and never sent.
- **Photos and videos.** They are read and processed on the device only and never leave it.
- **Location.** Photo GPS is only read on the device for the info sheet and "Share without location", and is never sent anywhere.
- **Advertising ID.** It was removed in 1.0.6 (the app has no ads).

## Related declarations
- **Advertising ID** (App content → Advertising ID): **No**, the app does not use an advertising ID.
- **Ads** (App content → Ads): **No, my app does not contain ads.**
- **Photo and video permissions** (App content → Photo and video permissions): the app uses `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` because **it is a gallery app: browsing, organising and editing the user's whole photo and video library is its core purpose**. The system photo picker cannot provide that.
- **Target audience:** 13+ (general audience). Don't select children's age groups unless you mean to; that brings the extra Families policy rules.
