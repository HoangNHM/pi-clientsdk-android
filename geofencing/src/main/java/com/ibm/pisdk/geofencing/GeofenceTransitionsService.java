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

package com.ibm.pisdk.geofencing;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * This Android service receives events from the Geofencing API and invokes the AEX geofence callback accordingly.
 */
public class GeofenceTransitionsService extends IntentService {
    /**
     * Logger for this class.
     */
    private static final Logger log = LoggingConfiguration.getLogger(GeofenceTransitionsService.class);

    public GeofenceTransitionsService() {
        super(GeofenceTransitionsService.class.getName());
    }

    public GeofenceTransitionsService(String name) {
        super(name);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        try {
            handleIntent(intent);
        } catch(Exception e) {
            log.error("exception occurred in handleIntent(): ", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleIntent(Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        int transition = event.getGeofenceTransition();
        ServiceConfig config = new ServiceConfig().fromIntent(intent);
        log.debug(String.format("in onHandleIntent() transition=%d, event=%s, config=%s", transition, event, config));
        if (event.hasError()) {
            log.error(String.format("Error code %d, triggering fences = %s, trigering location = %s", event.getErrorCode(), event.getTriggeringGeofences(), event.getTriggeringLocation()));
            return;
        }
        if ((transition == Geofence.GEOFENCE_TRANSITION_ENTER) || (transition == Geofence.GEOFENCE_TRANSITION_EXIT)) {
            // Get the geofences that were triggered. A single event can trigger multiple geofences.
            List<Geofence> triggeringGeofences = event.getTriggeringGeofences();
            log.debug("geofence transition: " + triggeringGeofences);
            PIGeofenceCallback callback = PIGeofencingService.callbackMap.get(intent.getStringExtra(PIGeofencingService.INTENT_ID));
            PIGeofencingService service = null;
            Context ctx = null;
            Class<? extends PIGeofenceCallbackService> clazz = null;
            // happens when the app is off
            if (callback == null) {
                //ctx = getApplicationContext();
                ctx = config.createContext(this);
                service = new PIGeofencingService(PIGeofencingService.MODE_SERVICE, clazz, null, ctx,
                    config.serverUrl, config.tenantCode, config.orgCode, config.username, config.password, (int) config.maxDistance);
                callback = service.geofenceCallback;
            } else {
                service = ((DelegatingGeofenceCallback) callback).service;
                ctx = service.context;
            }
            if (config.callbackServiceName != null) {
                try {
                    ClassLoader cl = ctx.getClassLoader();
                    clazz = (Class<? extends PIGeofenceCallbackService>) Class.forName(config.callbackServiceName, true, cl);
                } catch(Exception e) {
                    log.error(String.format("exeption loading callback service class '%s'", config.callbackServiceName), e);
                } catch(Error e) {
                    log.error(String.format("error loading callback service class '%s'", config.callbackServiceName), e);
                    throw e;
                }
            }
            List<PIGeofence> geofences = new ArrayList<>(triggeringGeofences.size());
            for (Geofence g : triggeringGeofences) {
                String code = g.getRequestId();
                List<PIGeofence> list = PIGeofence.find(PIGeofence.class, "code = ?", code);
                if (!list.isEmpty()) geofences.add(list.get(0));
            }
            log.debug(String.format("callback = %s, callback service name = %s, clazz=%s, triggered geofences = %s", callback, config.callbackServiceName, clazz, geofences));
            /*
            if (callback != null) {
                if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                    callback.onGeofencesEnter(geofences);
                } else {
                    callback.onGeofencesExit(geofences);
                }
            }
            if (clazz != null) {
                try {
                    Intent callbackIntent = new Intent(ctx, clazz);
                    config.geofences = geofences;
                    config.eventType = (transition == Geofence.GEOFENCE_TRANSITION_ENTER) ? ServiceConfig.EventType.ENTER : ServiceConfig.EventType.EXIT;
                    config.toIntent(callbackIntent);
                    log.error(String.format("sending config=%s", config));
                    ctx.startService(callbackIntent);
                } catch(Exception e) {
                    log.error(String.format("error starting callback service '%s'", config.callbackServiceName), e);
                }
            }
            */
        } else {
            log.error("invalid transition type: " + transition);
            //Log.e(LOG_TAG, "invalid transition type: " + transition);
        }
    }

    /*
    @Override
    public boolean equals(Object o) {
        if (o != null) {
            return o.getClass() == GeofenceTransitionsService.class;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
    */
}
