package com.example.chorevo.firebase

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.chorevo.core.DancingFigure
import com.example.chorevo.core.StageBackground
import com.example.chorevo.ui.theme.ChorevoTheme
import com.example.chorevo.ui.theme.PillShape
import kotlinx.coroutines.launch

private data class OnboardingPage(val title: String, val body: String)

private val ONBOARDING_PAGES = listOf(
    OnboardingPage(
        title = "Learn any routine",
        body = "Swipe through dance videos from creators and pick one to learn, at your own pace."
    ),
    OnboardingPage(
        title = "Live AI feedback",
        body = "Your camera tracks your body and hands and scores every move in real time, right on your phone."
    ),
    OnboardingPage(
        title = "Level up every week",
        body = "Earn points, keep your streak, unlock badges and climb the leaderboard with friends."
    )
)

/**
 * First-launch welcome carousel over a looping animated dancer. [onFinish]
 * is called from "Get started" on the last page or "Skip".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGES.size })
    val scope = rememberCoroutineScope()
    val colors = ChorevoTheme.colors
    val isLast = pagerState.currentPage == ONBOARDING_PAGES.lastIndex

    StageBackground(modifier = Modifier.testTag("onboarding")) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Chorevo",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                if (!isLast) {
                    TextButton(onClick = onFinish, modifier = Modifier.testTag("onboardingSkip")) {
                        Text("Skip", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            DancingFigure(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 24.dp)
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .testTag("onboardingPager")
            ) { page ->
                val item = ONBOARDING_PAGES[page]
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        item.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(ONBOARDING_PAGES.size) { i ->
                    val selected = i == pagerState.currentPage
                    val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dotWidth")
                    val color by animateColorAsState(
                        if (selected) colors.accent else Color.White.copy(alpha = 0.3f),
                        label = "dotColor"
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(width = width, height = 8.dp)
                            .clip(PillShape)
                            .background(color)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(PillShape)
                    .background(colors.brandGradient)
                    .testTag("onboardingNext"),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        if (isLast) {
                            onFinish()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    shape = PillShape
                ) {
                    Text(
                        if (isLast) "Get started" else "Next",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onGradient
                    )
                }
            }
        }
    }
}
