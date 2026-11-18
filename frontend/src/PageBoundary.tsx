import {Component,type ReactNode} from 'react'
import {Alert,Button,Card,Space} from 'antd'
export class PageBoundary extends Component<{children:ReactNode},{failed:boolean}>{
  state={failed:false}
  static getDerivedStateFromError(){return {failed:true}}
  render(){return this.state.failed?<Card><Alert showIcon type="error" title="页面暂时无法显示" description="已保存的任务和证据仍可在刷新后读取。"/><Space style={{marginTop:16}}><Button onClick={()=>window.location.reload()}>重新加载</Button><Button href="/">返回工作台</Button></Space></Card>:this.props.children}
}
