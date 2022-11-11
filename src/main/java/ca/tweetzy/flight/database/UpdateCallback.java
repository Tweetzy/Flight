/*
 * Flight
 * Copyright 2022 Kiran Hart
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ca.tweetzy.flight.database;

import org.jetbrains.annotations.Nullable;

/**
 * The current file has been created by Kiran Hart
 * Date Created: August 01 2021
 * Time Created: 1:50 a.m.
 * Usage of any code found within this class is prohibited unless given explicit permission otherwise
 */
public interface UpdateCallback {

    void accept(@Nullable Exception ex);

}
