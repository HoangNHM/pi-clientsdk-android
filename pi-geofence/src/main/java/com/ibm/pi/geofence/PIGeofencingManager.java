/**
 * Copyright (c) 2015-2016 IBM Corporation. All rights reserved.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.pi.geofence;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.ibm.pi.geofence.rest.HttpMethod;
import com.ibm.pi.geofence.rest.PIHttpService;
import com.ibm.pi.geofence.rest.PIJSONPayloadRequest;
import com.ibm.pi.geofence.rest.PIRequest;
import com.ibm.pi.geofence.rest.PIRequestCallback;
import com.ibm.pi.geofence.rest.PIRequestError;
import com.ibm.pisdk.geofencing.BuildConfig;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Provides an API to load geofences from, and send entry/exit to, the server.
 */
public class PIGeofencingManager {
    /**
     * Logger for this class.
     */
    private static final Logger log = LoggingConfiguration.getLogger(PIGeofencingManager.class.getSimpleName());
    static final String INTENT_ID = "PIGeofencingService";
    /**
     * Part of a request path pointing to the geofence connector.
     */
    static final String GEOFENCE_CONNECTOR_PATH = "conn-geofence/v1";
    /**
     * Part of a request path pointing to the pi conifg connector.
     */
    static final String CONFIG_CONNECTOR_PATH = "pi-config/v2";
    /**
     * Mode indicating this geofence manager is executed by the user-provided app.
     */
    static final int MODE_APP = 1;
    /**
     * Mode indicating this geofence manager is executed by the geofence transition service.
     */
    static final int MODE_GEOFENCE_EVENT = 2;
    /**
     * Mode indicating this geofence manager is executed by the significant location change service.
     */
    static final int MODE_MONITORING_REQUEST = 3;
    /**
     * Mode indicating this geofence manager is executed by the reboot handler service.
     */
    static final int MODE_REBOOT = 4;
    /**
     * The restful service which connects to and communicates with the Adaptive Experience server.
     */
    final PIHttpService httpService;
    /**
     * The Google API client.
     */
    GoogleApiClient googleApiClient;
    /**
     * The Android application context.
     */
    Context context;
    /**
     * Handles the geofences that are currently monitored.
     */
    final int maxDistance;
    /**
     * The callback used to notify the app of geofence events.
     */
    final DelegatingGeofenceCallback geofenceCallback;
    /**
     * An internal cache of the "add geofences" requests, so the geofence transition service knows which callback to invoke.
     */
    static final Map<String, PIGeofenceCallback> callbackMap = new ConcurrentHashMap<>();
    /**
     * Pending intent used to register a set of geofences.
     */
    private PendingIntent pendingIntent;
    /**
     * Provides uniquely identifying information for the device.
     */
    private final String deviceDescriptor;
    /**
     * Fully qualified class name of the user-provided callback service.
     */
    String callbackServiceName;
    /**
     * The settings of the application.
     */
    Settings settings;
    /**
     * The execution mode for this gefoence manager;
     * one of {@link #MODE_APP}, {@link #MODE_GEOFENCE_EVENT}, {@link #MODE_MONITORING_REQUEST} or {@link #MODE_REBOOT}.
     */
    final int mode;
    /**
     * A callback that immplements the Google API connection callback interfaces.
     */
    GoogleLocationAPICallback googleAPICallback;

    /**
     * Initialize this service.
     * @param context the Android application context.
     * @param baseURL base URL of the PI server.
     * @param tenantCode PI tenant code.
     * @param orgCode PI org code.
     * @param username PI username.
     * @param password PI password.
     * @param maxDistance distance threshold for sigificant location changes.
     * Defines the bounding box for the monitored geofences: square box with a {@code maxDistance} side centered on the current location.
     */
    PIGeofencingManager(Settings settings, int mode, Class<? extends PIGeofenceCallbackService> callbackServiceClass, Context context,
                        String baseURL, String tenantCode, String orgCode, String username, String password, int maxDistance) {
        log.debug("pi-geofence version " + BuildConfig.VERSION_NAME);
        this.mode = mode;
        this.maxDistance = maxDistance;
        if (callbackServiceClass != null) {
            this.callbackServiceName = callbackServiceClass.getName();
        }
        this.httpService = new PIHttpService(context, baseURL, tenantCode, orgCode, username, password);
        this.geofenceCallback = new DelegatingGeofenceCallback(this, null);
        callbackMap.put(INTENT_ID, this.geofenceCallback);
        this.context = context;
        this.settings = (settings != null) ? settings : new Settings(context);
        log.debug("PIGeofencingService() settings = " + this.settings);
        String desc = this.settings.getString("descriptor", null);
        this.deviceDescriptor = retrieveDeviceDescriptor();
        int n = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        log.debug("google play service availability = " + getGoogleAvailabilityAsText(n));
    }

    /**
     * Connect the Google API client. The synchronous / asynchronous mode depends on the {@link #mode} of this geofence manager.
     */
    private void connectGoogleAPI() {
        if ((context != null) && (mode != MODE_GEOFENCE_EVENT)) {
            googleAPICallback = new GoogleLocationAPICallback(this);
            googleApiClient = new GoogleApiClient.Builder(context)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(googleAPICallback)
                .addOnConnectionFailedListener(googleAPICallback)
                .build();
            log.debug("initGms() connecting to google play services ...");
            if ((mode == MODE_MONITORING_REQUEST) || (mode == MODE_REBOOT)) {
                try {
                    // can't run blockingConnect() on the UI thread
                    ConnectionResult result = new AsyncTask<Void, Void, ConnectionResult>() {
                        @Override
                        protected ConnectionResult doInBackground(Void... params) {
                            return googleApiClient.blockingConnect(60_000L, TimeUnit.MILLISECONDS);
                        }
                    }.execute().get();
                    log.debug(String.format("google api connection %s, result=%s", (result.isSuccess() ? "success" : "error"), result));
                } catch(Exception e) {
                    log.error("error while attempting connection to google api", e);
                }
            } else if (mode == MODE_APP) {
                googleApiClient.connect();
            }
        }
    }

    /**
     * This is the public factory method to create a geofence manager.
     * @param callbackServiceClass the cllass object for an optional user-provided callback service.
     * @param context the Android application context.
     * @param baseURL base URL of the PI server.
     * @param tenantCode PI tenant code.
     * @param orgCode PI org code.
     * @param username PI username.
     * @param password PI password.
     * @param maxDistance distance threshold for sigificant location changes.
     * Defines the bounding box for the monitored geofences: square box with a {@code maxDistance} side centered on the current location.
     */
    public static PIGeofencingManager newInstance(Class<? extends PIGeofenceCallbackService> callbackServiceClass, Context context,
        String baseURL, String tenantCode, String orgCode, String username, String password, int maxDistance) {
        return newInstance(null, MODE_APP, callbackServiceClass, context, baseURL, tenantCode, orgCode, username, password, maxDistance);
    }

    /**
     * Initialize this service.
     * @param context the Android application context.
     * @param baseURL base URL of the PI server.
     * @param tenantCode PI tenant code.
     * @param orgCode PI org code.
     * @param username PI username.
     * @param password PI password.
     * @param maxDistance distance threshold for sigificant location changes.
     * Defines the bounding box for the monitored geofences: square box with a {@code maxDistance} side centered on the current location.
     */
    static PIGeofencingManager newInstance(Settings settings, int mode, Class<? extends PIGeofenceCallbackService> callbackServiceClass, Context context,
                                           String baseURL, String tenantCode, String orgCode, String username, String password, int maxDistance) {
        PIGeofencingManager geofencingService = new PIGeofencingManager(
            settings, mode, callbackServiceClass, context, baseURL, tenantCode, orgCode, username, password, maxDistance);
        if (geofencingService.mode == MODE_APP) {
            geofencingService.updateSettings();
        }
        geofencingService.connectGoogleAPI();
        return geofencingService;
    }

    /**
     * Send a notification to the PI geofence connector as an HTTP request.
     * @param fences the geofences for which to send a notification.
     * @param type the type of geofence notification: either {@link GeofenceNotificationType#IN IN} or {@link GeofenceNotificationType#OUT OUT}.
     */
    void postGeofenceEvent(final List<PIGeofence> fences, final GeofenceNotificationType type) {
        if (httpService.getTenantCode() == null) {
            log.warn("cannot send geofence notification because the tenant code is undefined");
        } else if (httpService.getOrgCode() == null) {
            log.warn("cannot send geofence notification because the org code is undefined");
        } else {
            PIRequestCallback<JSONObject> callback = new PIRequestCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject result) {
                    log.debug("sucessfully notified connector for geofences " + fences);
                }

                @Override
                public void onError(PIRequestError error) {
                    log.error("error notifying connector for geofences " + fences + " : " + error.toString());
                }
            };
            JSONObject payload = GeofencingJSONUtils.toJSONGeofenceEvent(fences, type, deviceDescriptor, BuildConfig.VERSION_NAME);
            PIRequest<JSONObject> request = new PIJSONPayloadRequest(callback, HttpMethod.POST, payload.toString());
            String path = String.format(Locale.US, "%s/tenants/%s/orgs/%s", GEOFENCE_CONNECTOR_PATH, httpService.getTenantCode(), httpService.getOrgCode());
            request.setPath(path);
            request.setBasicAuthRequired(true);
            httpService.executeRequest(request);
        }
    }

    /**
     * Add the specified geofences to the monitored geofences.
     * @param geofences the geofences to add.
     */
    void monitorGeofences(List<PersistentGeofence> geofences) {
        log.debug("monitorGeofences(" + geofences + ")");
        if (!geofences.isEmpty()) {
            List<Geofence> list = new ArrayList<>(geofences.size());
            List<Geofence> noTriggerList = new ArrayList<>(geofences.size());
            Location last = LocationServices.FusedLocationApi.getLastLocation(googleApiClient);
            for (PersistentGeofence geofence : geofences) {
                Geofence fence = new Geofence.Builder().setRequestId(geofence.getCode())
                    .setCircularRegion(geofence.getLatitude(), geofence.getLongitude(), (float) geofence.getRadius())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setNotificationResponsiveness(10_000)
                    .setLoiteringDelay(300000)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build();

                Location location = new Location(LocationManager.NETWORK_PROVIDER);
                location.setLatitude(geofence.getLatitude());
                location.setLongitude(geofence.getLongitude());
                if ((mode != MODE_REBOOT) || (last == null) || (location.distanceTo(last) > geofence.getRadius())) {
                    list.add(fence);
                } else {
                    // if already in geofence, do not trigger upon registration.
                    noTriggerList.add(fence);
                }
            }
            registerFencesForMonitoring(list, GeofencingRequest.INITIAL_TRIGGER_ENTER);
            registerFencesForMonitoring(noTriggerList, 0);
            geofenceCallback.onGeofencesMonitored(PersistentGeofence.toPIGeofences(geofences));
        }
    }

    private void registerFencesForMonitoring(List<Geofence> fences, int initialTrigger) {
        if (!fences.isEmpty()) {
            GeofencingRequest request = new GeofencingRequest.Builder()
                .setInitialTrigger(initialTrigger)
                .addGeofences(fences).build();
            PendingIntent pi = getPendingIntent(INTENT_ID);
            LocationServices.GeofencingApi.addGeofences(googleApiClient, request, pi).setResultCallback(new ResultCallback<Status>() {
                @Override
                public void onResult(Status status) {
                    log.debug("add geofence request status " + status);
                }
            });
        }
    }

    /**
     * Remove the specified geofences from the monitored geofences.
     * @param geofences the geofences to remove.
     */
    void unmonitorGeofences(List<PersistentGeofence> geofences) {
        log.debug("unmonitorGeofences(" + geofences + ")");
        if (!geofences.isEmpty()) {
            List<String> uuidsToRemove = new ArrayList<>(geofences.size());
            for (PersistentGeofence g : geofences) {
                uuidsToRemove.add(g.getCode());
            }
            LocationServices.GeofencingApi.removeGeofences(googleApiClient, uuidsToRemove);
            geofenceCallback.onGeofencesUnmonitored(PersistentGeofence.toPIGeofences(geofences));
        }
    }

    /**
     * Load a set of geofences from a reosurce file.
     * @param resource the path to the resource to load the geofences from.
     */
    public void loadGeofencesFromResource(final String resource, final PIRequestCallback<List<PIGeofence>> userCallback) {
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            private GeofenceList geofenceList;
            private PIRequestError error;

            @Override
            protected Void doInBackground(Void... params) {
                ZipInputStream zis = null;
                try {
                    InputStream is = getClass().getClassLoader().getResourceAsStream(resource);
                    zis = new ZipInputStream(is);
                    ZipEntry entry;
                    Map<String, PersistentGeofence> allGeofences = new HashMap<>();
                    String maxSyncDate = null;
                    while ((entry = zis.getNextEntry()) != null) {
                        byte[] bytes = GeofencingUtils.loadBytes(zis);
                        if (bytes != null) {
                            int fileSize = bytes.length;
                            JSONObject json = new JSONObject(new String(bytes, "UTF-8"));
                            bytes = null; // the byte[] may be large, we make sure it can be GC-ed ASAP
                            GeofenceList list = GeofencingJSONUtils.parseGeofences(json);
                            List<PersistentGeofence> geofences = list.getGeofences();
                            if ((geofences != null) && !geofences.isEmpty()) {
                                PersistentGeofence.saveInTx(geofences);
                                log.debug(String.format(Locale.US, "loaded %,d geofences from resource '[%s]/%s' (%,d bytes)",
                                    geofences.size(), resource, entry.getName(), fileSize));
                            }

                            if (list.getLastSyncDate() != null) {
                                if ((maxSyncDate == null) || (list.getLastSyncDate().compareTo(maxSyncDate) > 0)) {
                                    maxSyncDate = list.getLastSyncDate();
                                }
                            }
                            for (PersistentGeofence pg: list.getGeofences()) {
                                allGeofences.put(pg.getCode(), pg);
                            }
                        }
                        else {
                            log.debug(String.format("the zip entry [%s]/%s is empty", resource, entry.getName()));
                        }
                    }
                    if (maxSyncDate != null) {
                        settings.putString(ServiceConfig.EXTRA_LAST_SYNC_DATE, maxSyncDate).commit();
                    }
                    geofenceList = new GeofenceList(new ArrayList<>(allGeofences.values()));
                    log.debug(String.format(Locale.US, "loaded %,d geofences from resource '[%s]', maxSyncDate=%s",
                        allGeofences.size(), resource, maxSyncDate));
                } catch(Exception e) {
                    log.error(String.format("error loading resource %s", resource), e);
                    error = new PIRequestError(-1, e, String.format("error loading resource '%s'", resource));
                } finally {
                    try {
                        zis.close();
                    } catch(Exception e) {
                        log.error(String.format("error closing zip input stream for resource %s", resource), e);
                        if (error == null) {
                            error = new PIRequestError(-1, e, String.format("error loading resource '%s'", resource));
                        }
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                if (userCallback != null) {
                    if (error != null) {
                        userCallback.onError(error);
                    } else {
                        List<PIGeofence> geofences = new ArrayList<>(geofenceList.getGeofences().size());
                        for (PersistentGeofence pg: geofenceList.getGeofences()) {
                            geofences.add(pg.toPIGeofence());
                        }
                        userCallback.onSuccess(geofences);
                    }
                }
            }
        };
        task.execute();
    }

    /**
     * Get a pending intent for the specified callback.
     * @param geofenceCallbackUuid the uuid of an internally mapped callback.
     * @return a <code>PendingIntent</code> instance.
     */
    private PendingIntent getPendingIntent(String geofenceCallbackUuid) {
        if (pendingIntent == null) {
            Intent intent = new Intent(context, GeofenceTransitionsService.class);
            ServiceConfig config = new ServiceConfig().fromGeofencingManager(this);
            config.populateFromSettings(settings);
            config.toIntent(intent);
            intent.putExtra(INTENT_ID, geofenceCallbackUuid);
            intent.setClass(context, GeofenceTransitionsService.class);
            pendingIntent = PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return pendingIntent;
    }

    /**
     * Load geofences from the local database if they are present, or from the server if not.
     */
    public void loadGeofences() {
        if (httpService.getServerURL() != null) {
            log.debug("loadGeofences() loading geofences from the server");
            loadGeofencesFromServer();
        } else {
            log.debug("loadGeofences() found geofences in local database");
            setInitialLocation();
        }
    }

    /**
     * Query the geofences from the server, based on the current anchor.
     */
    private void loadGeofencesFromServer() {
        if (PersistentGeofence.count(PersistentGeofence.class) <= 0) {
            loadGeofencesFromServer(null);
        } else {
            loadGeofencesFromServer(settings.getString(ServiceConfig.EXTRA_LAST_SYNC_DATE, null));
        }
    }

    /**
     * Query the geofences from the server, based on the current last sync date.
     */
    private void loadGeofencesFromServer(String lastSyncDate) {
        if ((httpService.getTenantCode() != null) && (httpService.getOrgCode() != null)) {
            PIRequestCallback<JSONObject> cb = new PIRequestCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject result) {
                    try {
                        GeofenceList list = GeofencingJSONUtils.parseGeofences(result);
                        if (list.getLastSyncDate() != null) {
                            settings.putString(ServiceConfig.EXTRA_LAST_SYNC_DATE, list.getLastSyncDate()).commit();
                        }
                        List<PersistentGeofence> geofences = list.getGeofences();
                        if (!geofences.isEmpty()) {
                            PersistentGeofence.saveInTx(geofences);
                            setInitialLocation();
                        }
                        if (!geofences.isEmpty() || !list.getDeletedGeofenceCodes().isEmpty()) {
                            ServiceConfig config = new ServiceConfig().fromGeofencingManager(PIGeofencingManager.this);
                            Class<? extends PIGeofenceCallbackService> clazz = config.loadCallbackServiceClass(context);
                            Intent callbackIntent = new Intent(context, clazz);
                            config.geofences = geofences;
                            config.eventType = ServiceConfig.EventType.SERVER_SYNC;
                            config.toIntent(callbackIntent);
                            log.debug(String.format("sending config for server sync event: %s", config));
                            context.startService(callbackIntent);
                        }
                        log.debug("loadGeofences() got " + list.getGeofences().size() + " geofences");
                    } catch (Exception e) {
                        PIRequestError error = new PIRequestError(-1, e, "error while parsing JSON");
                        log.debug(error.toString());
                    }
                }

                @Override
                public void onError(PIRequestError error) {
                    log.debug(error.toString());
                }
            };
            PIJSONPayloadRequest request = new PIJSONPayloadRequest(cb, HttpMethod.GET, null);
            request.setPath(String.format("%s/tenants/%s/orgs/%s/geofences", CONFIG_CONNECTOR_PATH, httpService.getTenantCode(), httpService.getOrgCode()));
            if (lastSyncDate != null) {
                request.addParameter("lastSyncDate", lastSyncDate);
            }
            httpService.executeRequest(request);
        }
    }

    /**
     * Set the initial location upon starting the app and trigger the registration of geofences, if any, around this location.
     */
    private void setInitialLocation() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Location last = LocationServices.FusedLocationApi.getLastLocation(googleApiClient);
                log.debug("setInitialLocation() last location = " + last);
                if (last != null) {
                    new LocationUpdateReceiver(PIGeofencingManager.this).onLocationChanged(last, true);
                }
            }
        };
        new Thread(r).start();
    }

    /**
     * Converts a Google play services availability code into a displayable string. Used for debugging and tracing purposes.
     * @param availabilityCode the google api connection result to convert.
     * @return a readable string.
     */
    private String getGoogleAvailabilityAsText(int availabilityCode) {
        switch(availabilityCode) {
            case ConnectionResult.SUCCESS: return "SUCCESS";
            case ConnectionResult.SERVICE_MISSING: return "SERVICE_MISSING";
            case ConnectionResult.SERVICE_UPDATING: return "SERVICE_UPDATING";
            case ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED: return "SERVICE_VERSION_UPDATE_REQUIRED";
            case ConnectionResult.SERVICE_DISABLED: return "SERVICE_DISABLED";
            case ConnectionResult.SERVICE_INVALID: return "SERVICE_INVALID";
            default: return "undefined";
        }
    }

    private void updateSettings() {
        settings.putString(ServiceConfig.EXTRA_SERVER_URL, httpService.getServerURL());
        settings.putString(ServiceConfig.EXTRA_TENANT_CODE, httpService.getTenantCode());
        settings.putString(ServiceConfig.EXTRA_ORG_CODE, httpService.getOrgCode());
        settings.putString(ServiceConfig.EXTRA_USERNAME, httpService.getUsername());
        settings.putString(ServiceConfig.EXTRA_PASSWORD, httpService.getPassword());
        settings.putString(ServiceConfig.EXTRA_CALLBACK_SERVICE_NAME, callbackServiceName);
        settings.putInt(ServiceConfig.EXTRA_MAX_DISTANCE, maxDistance);
        settings.commit();
    }

    /**
     * Retrieve the last used device descritpor, if any. If none exist, one is created from the {@link com.ibm.pisdk.PIDeviceInfo PIDeviceInfo} API.
     * @return the device descriptor.
     */
    String retrieveDeviceDescriptor() {
        String result = settings.getString(GeofencingDeviceInfo.PI_DESCRIPTOR_KEY, null);
        if (result == null) {
            SharedPreferences prefs = context.getSharedPreferences(GeofencingDeviceInfo.PI_SHARED_PREF, Context.MODE_PRIVATE);
            result = prefs.getString(GeofencingDeviceInfo.PI_DESCRIPTOR_KEY, null);
            if (result == null) {
                GeofencingDeviceInfo info = new GeofencingDeviceInfo(context);
                result = info.getDescriptor();
                prefs.edit().putString(GeofencingDeviceInfo.PI_DESCRIPTOR_KEY, result).apply();
            }
            settings.putString(GeofencingDeviceInfo.PI_DESCRIPTOR_KEY, result).commit();
        }
        return result;
    }
}
