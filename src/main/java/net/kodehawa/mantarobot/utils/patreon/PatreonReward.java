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

@SuppressWarnings("unused") // all most all of them get reported as unused, but they are needed
public enum PatreonReward {
    NONE(0),
    SUPPORTER(1),
    FRIEND(2),
    PATREON_BOT(3),
    MILESTONER(4),
    SERVER_SUPPORTER(8),
    AWOOSOME(25),
    FUNDER(35),
    BUT_WHY(50);

    private final long keyAmount;
    PatreonReward(long keyAmount) {
        this.keyAmount = keyAmount;
    }

    public long getKeyAmount() {
        return keyAmount;
    }
}
