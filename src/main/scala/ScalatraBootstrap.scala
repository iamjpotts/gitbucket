import java.util.EnumSet
import javax.servlet._

import gitbucket.core.controller.{ReleaseController, _}
import gitbucket.core.service.{SystemSettingsFileService, SystemSettingsService}
import gitbucket.core.servlet._
import gitbucket.core.util.Directory
import org.scalatra._

class ScalatraBootstrap extends LifeCycle with SystemSettingsService with SystemSettingsFileService {
  override def init(context: ServletContext): Unit = {

    val settings = loadSystemSettings()
    if (settings.baseUrl.exists(_.startsWith("https://"))) {
      context.getSessionCookieConfig.setSecure(true)
    }

    // The single Directory instance this process uses, provided explicitly to every controller
    // below rather than having each one reach for the Directory object independently.
    val directory: Directory = Directory

    // Register TransactionFilter at first
    context.addFilter("transactionFilter", new TransactionFilter)
    context
      .getFilterRegistration("transactionFilter")
      .addMappingForUrlPatterns(EnumSet.allOf(classOf[DispatcherType]), true, "/*")
    context.addFilter("gitAuthenticationFilter", new GitAuthenticationFilter)
    context
      .getFilterRegistration("gitAuthenticationFilter")
      .addMappingForUrlPatterns(EnumSet.allOf(classOf[DispatcherType]), true, "/git/*")
    context.addFilter("apiAuthenticationFilter", new ApiAuthenticationFilter)
    context
      .getFilterRegistration("apiAuthenticationFilter")
      .addMappingForUrlPatterns(EnumSet.allOf(classOf[DispatcherType]), true, "/api/*")

    // Register controllers
    context.mount(new PreProcessController(directory), "/*")

    context.addFilter("pluginControllerFilter", new PluginControllerFilter)
    context
      .getFilterRegistration("pluginControllerFilter")
      .addMappingForUrlPatterns(EnumSet.allOf(classOf[DispatcherType]), true, "/*")

    context.mount(new FileUploadController(directory), "/upload")

    val filter = new CompositeScalatraFilter()
    filter.mount(new IndexController(directory), "/")
    filter.mount(new ApiController(directory), "/api/v3")
    filter.mount(new SystemSettingsController(directory), "/admin")
    filter.mount(new DashboardController(directory), "/*")
    filter.mount(new AccountController(directory), "/*")
    filter.mount(new RepositoryViewerController(directory), "/*")
    filter.mount(new WikiController(directory), "/*")
    filter.mount(new LabelsController(directory), "/*")
    filter.mount(new PrioritiesController(directory), "/*")
    filter.mount(new MilestonesController(directory), "/*")
    filter.mount(new IssuesController(directory), "/*")
    filter.mount(new PullRequestsController(directory), "/*")
    filter.mount(new ReleaseController(directory), "/*")
    filter.mount(new RepositorySettingsController(directory), "/*")

    context.addFilter("compositeScalatraFilter", filter)
    context
      .getFilterRegistration("compositeScalatraFilter")
      .addMappingForUrlPatterns(EnumSet.allOf(classOf[DispatcherType]), true, "/*")

    // Create GITBUCKET_HOME directory if it does not exist
    val dir = new java.io.File(directory.GitBucketHome)
    if (!dir.exists) {
      dir.mkdirs()
    }
  }

  override def destroy(context: ServletContext): Unit = {
    Database.closeDataSource()
  }
}
