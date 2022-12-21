import { HttpClient } from '@angular/common/http';
import { AfterViewChecked, Component, DoCheck, ElementRef, ViewChild } from '@angular/core';

@Component({
  selector: 'app-root',
  template: `
    <div class="status">
      <div>
        <a href="#status" (click)="status()">Update Status</a>&nbsp;

        <a *ngIf="statusInfo.automationSuspended" href="#resume" (click)="resume()">Resume</a> 
        <a *ngIf="!statusInfo.automationSuspended" ref="#suspend" (click)="suspend()">Suspend</a> 
      </div>
      <pre>{{statusText}}</pre>
    </div>

    <div class="logs" #logs>
      <div *ngFor="let m of logMessages">{{m}}</div>
    </div>
  `,
  styleUrls: ['./app.component.scss']
})
export class AppComponent implements AfterViewChecked, DoCheck {

  public statusText = "";

  public statusInfo: any = {};

  @ViewChild( "logs", {read: ElementRef} )
  private logs: ElementRef; 

  public logMessages: string[] = [];
  private logginService = new LogWebSocket( value => {

    if( this.logMessages.indexOf( value ) < 0 ) {
        this.logMessages.push( value );

        let delCnt = this.logMessages.length - 500;
        if( delCnt > 0 ) {
          this.logMessages.splice( 0, delCnt );
        }

        this.scrollDown = true;
    }
  } );

  constructor(private http: HttpClient ) { 
    this.status();
    this.logginService.connect();

    console.log( "http", http );

    
    setInterval( () => {
      this.status();
    }, 5000 );
  }

  private scrollDown = false;

  ngAfterViewChecked(): void {

    if( this.scrollDown ) {
      this.scrollDown = false;
      let logs = this.logs.nativeElement as HTMLDivElement;

      logs.scrollTo( 0, logs.scrollHeight );
    }
  }


  ngDoCheck(): void {
      
  }

  public status() {
    this.http.get( "cmd/status" ).subscribe( value => {
      this.statusUpdated( value );
    })
  }

  public resume() {
    this.http.get( "cmd/resume" ).subscribe( value => {
      this.statusUpdated( value );
    })
  }
  public suspend() {
    this.http.get( "cmd/suspend" ).subscribe( value => {
      this.statusUpdated( value );
    })
  }

  protected statusUpdated( statusInfo: Object ) {
    this.statusInfo = statusInfo;
    this.statusText = JSON.stringify( statusInfo, void 0, "\t" );
  }

  

  title = 'KStarsCluster';
}

class LogWebSocket
{
  private keepAlive: any;
  private ws: WebSocket;

  constructor( private logMessage: ( value: string ) => void ) {

  }

  public connect() {

    var codeMap: any = {};
    codeMap[1000] = "(NORMAL)";
    codeMap[1001] = "(ENDPOINT_GOING_AWAY)";
    codeMap[1002] = "(PROTOCOL_ERROR)";
    codeMap[1003] = "(UNSUPPORTED_DATA)";
    codeMap[1004] = "(UNUSED/RESERVED)";
    codeMap[1005] = "(INTERNAL/NO_CODE_PRESENT)";
    codeMap[1006] = "(INTERNAL/ABNORMAL_CLOSE)";
    codeMap[1007] = "(BAD_DATA)";
    codeMap[1008] = "(POLICY_VIOLATION)";
    codeMap[1009] = "(MESSAGE_TOO_BIG)";
    codeMap[1010] = "(HANDSHAKE/EXT_FAILURE)";
    codeMap[1011] = "(SERVER/UNEXPECTED_CONDITION)";
    codeMap[1015] = "(INTERNAL/TLS_ERROR)";

    let uri = window.location.protocol.replace( "http", "ws" ) + "//" + window.location.host + "/logging/";
    try {
      this.ws = new WebSocket(uri);
      this.ws.onopen = () => {
        this.keepAlive = setInterval( () => {
          this.ws.send( "KEEP_ALIVE" );
        }, 1000 );
      };

      this.ws.onmessage = m => {
        if(m.data) {
          this.logMessage(m.data);
        }
      };

      this.ws.onclose = closeEvent => {
        var codeStr = codeMap[closeEvent.code];
        this.logMessage("closed: " + closeEvent.code + " " + codeStr + " " + closeEvent.reason);

        clearInterval( this.keepAlive );

        setTimeout( () => {
          this.connect();
        }, 1000 );
      };
      this.ws.onerror = evt => {
        this.logMessage("error: " + evt );
      };
    } catch(exception) {
      this.logMessage("error: " + exception);
    }  
  }            
}