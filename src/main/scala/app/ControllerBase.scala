package app

import _root_.util.Directory._
import _root_.util.{SystemSettings, StringUtil, FileUtil, Validations}
import SystemSettings._
import org.scalatra._
import org.scalatra.json._
import org.json4s._
import jp.sf.amateras.scalatra.forms._
import org.apache.commons.io.FileUtils
import service.AccountService
import javax.servlet.http.{HttpServletResponse, HttpSession, HttpServletRequest}
import javax.servlet._
import java.text.SimpleDateFormat
import scala.Some
import model.Account

/**
 * Provides generic features for controller implementations.
 */
abstract class ControllerBase extends ScalatraFilter
  with ClientSideValidationFormSupport with JacksonJsonSupport with Validations {
  self: AccountService =>

  implicit val jsonFormats = DefaultFormats

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
    val httpRequest    = request.asInstanceOf[HttpServletRequest]
    val httpResponse   = response.asInstanceOf[HttpServletResponse]
    val contextPath    = request.getServletContext.getContextPath
    val path           = httpRequest.getRequestURI.substring(contextPath.length)
    val systemSettings = SystemSettings()

    val context = Context(servletContext.getContextPath, loginAccount(systemSettings), currentURL, httpRequest, systemSettings)
    httpRequest.setAttribute("CONTEXT", context)

    if(path.startsWith("/console/")){
      val account = httpRequest.getCookies.find(_.getName == "gitbucket_login").flatMap { cookie =>
        try {
          getAccountByUserName(StringUtil.decrypt(cookie.getValue, systemSettings.blowfishKey))
        } catch {
          case e: Exception => None
        }
      } orNull

      if(account == null){
        // Redirect to login form
        httpResponse.sendRedirect(context + "/signin?" + path)
      } else if(account.isAdmin){
        // H2 Console (administrators only)
        chain.doFilter(request, response)
      } else {
        // Redirect to dashboard
        httpResponse.sendRedirect(context + "/")
      }
    } else if(path.startsWith("/git/")){
      // Git repository
      chain.doFilter(request, response)
    } else {
      // Scalatra actions
      super.doFilter(request, response, chain)
    }
  }

  /**
   * Returns the context object for the request.
   */
  implicit def context: Context = request.getAttribute("CONTEXT").asInstanceOf[Context]

  private def currentURL: String = {
    val queryString = request.getQueryString
    request.getRequestURI + (if(queryString != null) "?" + queryString else "")
  }

  protected def loginAccount(systemSettings: SystemSettings): Option[Account] = {
    cookies.get("gitbucket_login") match {
      case Some(value) => {
        try {
          val userName = StringUtil.decrypt(value, systemSettings.blowfishKey)
          getAccountByUserName(userName)
        } catch {
          case e: Exception => None
        }
      }
      case _ => None
    }
  }

  protected def baseUrl = {
    val url = request.getRequestURL.toString
    url.substring(0, url.length - (request.getRequestURI.length - request.getContextPath.length))
  }

  def ajaxGet(path : String)(action : => Any) : Route = {
    super.get(path){
      request.setAttribute("AJAX", "true")
      action
    }
  }

  override def ajaxGet[T](path : String, form : MappingValueType[T])(action : T => Any) : Route = {
    super.ajaxGet(path, form){ form =>
      request.setAttribute("AJAX", "true")
      action(form)
    }
  }

  def ajaxPost(path : String)(action : => Any) : Route = {
    super.post(path){
      request.setAttribute("AJAX", "true")
      action
    }
  }

  override def ajaxPost[T](path : String, form : MappingValueType[T])(action : T => Any) : Route = {
    super.ajaxPost(path, form){ form =>
      request.setAttribute("AJAX", "true")
      action(form)
    }
  }

  protected def NotFound() = {
    if(request.getAttribute("AJAX") == null){
      org.scalatra.NotFound(html.error("Not Found"))
    } else {
      org.scalatra.NotFound()
    }
  }

  protected def Unauthorized()(implicit context: app.Context) = {
    if(request.getAttribute("AJAX") == null){
      if(context.loginAccount.isDefined){
        org.scalatra.Unauthorized(redirect("/"))
      } else {
        org.scalatra.Unauthorized(redirect("/signin?" + currentURL))
      }
    } else {
      org.scalatra.Unauthorized()
    }
  }

}

/**
 * Context object for the current request.
 */
case class Context(path: String, loginAccount: Option[Account], currentUrl: String, request: HttpServletRequest,
                   systemSettings: SystemSettings){

  /**
   * Get object from cache.
   *
   * If object has not been cached with the specified key then retrieves by given action.
   * Cached object are available during a request.
   */
  def cache[A](key: String)(action: => A): A = {
    Option(request.getAttribute("cache." + key).asInstanceOf[A]).getOrElse {
      val newObject = action
      request.setAttribute("cache." + key, newObject)
      newObject
    }
  }

}

/**
 * Base trait for controllers which manages account information.
 */
trait AccountManagementControllerBase extends ControllerBase with FileUploadControllerBase {
  self: AccountService  =>

  protected def updateImage(userName: String, fileId: Option[String], clearImage: Boolean): Unit = {
    if(clearImage){
      getAccountByUserName(userName).flatMap(_.image).map { image =>
        new java.io.File(getUserUploadDir(userName), image).delete()
        updateAvatarImage(userName, None)
      }
    } else {
      fileId.map { fileId =>
        val filename = "avatar." + FileUtil.getExtension(getUploadedFilename(fileId).get)
        FileUtils.moveFile(
          getTemporaryFile(fileId),
          new java.io.File(getUserUploadDir(userName), filename)
        )
        updateAvatarImage(userName, Some(filename))
      }
    }
  }

  protected def uniqueUserName: Constraint = new Constraint(){
    override def validate(name: String, value: String): Option[String] =
      getAccountByUserName(value).map { _ => "User already exists." }
  }

  protected def uniqueMailAddress(paramName: String = ""): Constraint = new Constraint(){
    override def validate(name: String, value: String, params: Map[String, String]): Option[String] =
      getAccountByMailAddress(value)
        .filter { x => if(paramName.isEmpty) true else Some(x.userName) != params.get(paramName) }
        .map    { _ => "Mail address is already registered." }
  }

}

/**
 * Base trait for controllers which needs file uploading feature.
 */
trait FileUploadControllerBase {

  def generateFileId: String =
    new SimpleDateFormat("yyyyMMddHHmmSSsss").format(new java.util.Date(System.currentTimeMillis))

  def TemporaryDir(implicit session: HttpSession): java.io.File =
    new java.io.File(GitBucketHome, s"tmp/_upload/${session.getId}")

  def getTemporaryFile(fileId: String)(implicit session: HttpSession): java.io.File =
    new java.io.File(TemporaryDir, fileId)

  //  def removeTemporaryFile(fileId: String)(implicit session: HttpSession): Unit =
  //    getTemporaryFile(fileId).delete()

  def removeTemporaryFiles()(implicit session: HttpSession): Unit =
    FileUtils.deleteDirectory(TemporaryDir)

  def getUploadedFilename(fileId: String)(implicit session: HttpSession): Option[String] = {
    val filename = Option(session.getAttribute("upload_" + fileId).asInstanceOf[String])
    if(filename.isDefined){
      session.removeAttribute("upload_" + fileId)
    }
    filename
  }

}