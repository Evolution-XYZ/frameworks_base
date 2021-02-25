/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.MediaHost
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.customize.QSCustomizerController
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.statusbar.FeatureFlags
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QuickQSPanelControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var quickQSPanel: QuickQSPanel
    @Mock
    private lateinit var qsTileHost: QSTileHost
    @Mock
    private lateinit var qsCustomizerController: QSCustomizerController
    @Mock
    private lateinit var mediaHost: MediaHost
    @Mock
    private lateinit var metricsLogger: MetricsLogger
    private val uiEventLogger = UiEventLoggerFake()
    @Mock
    private lateinit var qsLogger: QSLogger
    private val dumpManager = DumpManager()
    @Mock
    private lateinit var tile: QSTile
    @Mock
    private lateinit var tileLayout: TileLayout
    @Mock
    private lateinit var tileView: QSTileView
    @Mock
    private lateinit var featureFlags: FeatureFlags

    private lateinit var controller: QuickQSPanelController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(quickQSPanel.tileLayout).thenReturn(tileLayout)
        `when`(quickQSPanel.dumpableTag).thenReturn("")
        `when`(qsTileHost.createTileView(any(), anyBoolean())).thenReturn(tileView)

        controller = QuickQSPanelController(
                quickQSPanel,
                qsTileHost,
                qsCustomizerController,
                false,
                mediaHost,
                metricsLogger,
                uiEventLogger,
                qsLogger,
                dumpManager,
                featureFlags
        )

        controller.init()
    }

    @After
    fun tearDown() {
        controller.onViewDetached()
    }

    @Test
    fun testTileSublistWithFewerTiles_noCrash() {
        `when`(quickQSPanel.numQuickTiles).thenReturn(3)

        `when`(qsTileHost.tiles).thenReturn(listOf(tile, tile))

        controller.setTiles()
    }

    @Test
    fun testTileSublistWithTooManyTiles() {
        val limit = 3
        `when`(quickQSPanel.numQuickTiles).thenReturn(limit)
        `when`(qsTileHost.tiles).thenReturn(listOf(tile, tile, tile, tile))

        controller.setTiles()

        verify(quickQSPanel, times(limit)).addTile(any())
    }
}