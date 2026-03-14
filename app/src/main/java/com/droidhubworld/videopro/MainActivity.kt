package com.droidhubworld.videopro

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.droidhubworld.videopro.ui.screen.home.Home
import com.droidhubworld.videopro.ui.screen.main.MainScreen
import com.droidhubworld.videopro.ui.screen.rough.RoughScreen
import com.droidhubworld.videopro.ui.theme.VideoProTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VideoProTheme {
                val navController = rememberNavController()
                
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "main",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("main") {
                            MainScreen(
                                onNavigateToHome = { uri ->
                                    val encodedUri = Uri.encode(uri.toString())
                                    navController.navigate("home?uri=$encodedUri")
                                },
                                onNavigateToRough = {
                                    navController.navigate("rough")
                                }
                            )
                        }
                        composable(
                            route = "home?uri={uri}",
                            arguments = listOf(
                                navArgument("uri") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                }
                            )
                        ) { backStackEntry ->
                            val uriString = backStackEntry.arguments?.getString("uri")
                            val initialUri = uriString?.let { Uri.parse(Uri.decode(it)) }
                            Home(initialUri = initialUri)
                        }
                        composable("rough") {
                            RoughScreen()
                        }
                    }
                }
            }
        }
    }
}
