package dev.patchpipe.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.patchpipe.app.engine.PatchpipeEngine
import dev.patchpipe.app.ui.edit.PipeEditScreen
import dev.patchpipe.app.ui.list.PipeListScreen

@Composable
fun PatchpipeAppUi() {
    val nav = rememberNavController()
    val engine = PatchpipeEngine.from(LocalContext.current)
    val vm: PatchpipeViewModel = viewModel(factory = PatchpipeViewModel.factory(engine))
    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            PipeListScreen(
                vm = vm,
                onAdd = { nav.navigate("edit/new") },
                onEdit = { id -> nav.navigate("edit/$id") },
            )
        }
        composable(
            "edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: "new"
            PipeEditScreen(
                vm = vm,
                pipeId = id,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
