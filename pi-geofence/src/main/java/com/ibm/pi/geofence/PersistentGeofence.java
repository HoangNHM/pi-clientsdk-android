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

import com.orm.SugarRecord;
import com.orm.dsl.Unique;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Instances of this class represent geofences.
 */
public class PersistentGeofence extends SugarRecord implements Serializable {
    @Unique
    private String code = "";
    private String name;
    private String description;
    private double latitude;
    private double longitude;
    private double radius;
    private long createdTimestamp;
    private long updatedTimestamp;

    public PersistentGeofence() {
    }

    public PersistentGeofence(String code, String name, String description, double latitude, double longitude, double radius) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radius = radius;
    }

    public String getCode() {
        return code;
    }

    void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(long createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public long getUpdatedTimestamp() {
        return updatedTimestamp;
    }

    public void setUpdatedTimestamp(long updatedTimestamp) {
        this.updatedTimestamp = updatedTimestamp;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PersistentGeofence that = (PersistentGeofence) o;
        return code.equals(that.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }

    PIGeofence toPIGeofence() {
        return new PIGeofence(code, name, description, latitude, longitude, radius);
    }

    static PersistentGeofence fromPIGeofence(PIGeofence piGeofence) {
        return new PersistentGeofence(piGeofence.getCode(), piGeofence.getName(), piGeofence.getDescription(),
            piGeofence.getLatitude(), piGeofence.getLongitude(), piGeofence.getRadius());
    }

    static List<PIGeofence> toPIGeofences(List<PersistentGeofence> list) {
        List<PIGeofence> result = new ArrayList<>(list.size());
        for (PersistentGeofence pg: list) {
            result.add(pg.toPIGeofence());
        }
        return result;
    }
}