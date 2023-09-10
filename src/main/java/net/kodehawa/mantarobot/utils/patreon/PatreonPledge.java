/*
 * Copyright (C) 2016 Kodehawa
 *
 * Mantaro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Mantaro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 *
 */

package net.kodehawa.mantarobot.utils.patreon;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.beans.ConstructorProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PatreonPledge {
    private double amount;
    private PatreonReward reward;
    private final boolean active;

    @ConstructorProperties({"amount", "active", "reward"})
    @JsonCreator
    public PatreonPledge(double amount, boolean active, PatreonReward reward) {
        this.amount = amount;
        this.reward = reward;
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }

    @SuppressWarnings("unused")
    public double getAmount() {
        return amount;
    }

    @SuppressWarnings("unused")
    public void setAmount(double amount) {
        this.amount = amount;
    }

    public PatreonReward getReward() {
        return reward;
    }

    @SuppressWarnings("unused")
    public void setReward(PatreonReward reward) {
        this.reward = reward;
    }

    public String toString() {
        return "Active: %s, Amount: %.1f, Tier: %s".formatted(active, amount, reward);
    }
}
