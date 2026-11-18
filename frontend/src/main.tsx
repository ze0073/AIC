import React, {useEffect,useState} from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider, App as AntApp } from 'antd'
import { BrowserRouter } from 'react-router-dom'
import zhCN from 'antd/locale/zh_CN'
import App from './SessionApp'
import { MotionLayer } from './MotionLayer'
import './styles.css'
import './industrial.css'
import './cards.css'
import './motion.css'
import './image-backgrounds.css'
import './workspace-accent.css'
import './workbench-intro.css'
import './browser-login-boot.css'
import './visual-polish.css'
import './brand-logo.css'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 5000 }, mutations: { retry: false } },
})

function PlatformMotion({children}:{children:React.ReactNode}) {
  const [reduced,setReduced]=useState(()=>window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  useEffect(()=>{
    const media=window.matchMedia('(prefers-reduced-motion: reduce)'),change=()=>setReduced(media.matches)
    media.addEventListener('change',change)
    return ()=>media.removeEventListener('change',change)
  },[])
  return <ConfigProvider theme={{token:{motion:!reduced}}}>{children}</ConfigProvider>
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={{
      token: {
        colorPrimary: '#657719',
        colorInfo: '#657719',
        colorSuccess: '#2f9567',
        colorWarning: '#c78632',
        colorText: '#252a25',
        colorTextSecondary: '#72786d',
        colorBgLayout: '#eeefe9',
        borderRadius: 4,
        borderRadiusLG: 6,
        controlHeight: 40,
        fontFamily: '"Segoe UI Variable", "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
      },
      components: {
        Button: {
          primaryShadow: 'none', fontWeight: 650,
          colorPrimary: '#dcef4a', colorPrimaryHover: '#e4f575', colorPrimaryActive: '#c3d738', primaryColor: '#23291b',
          defaultHoverColor: '#4d6015', defaultHoverBg: '#f3f6e7', defaultHoverBorderColor: '#9cad45',
          defaultActiveColor: '#405112', defaultActiveBg: '#e8efd2', defaultActiveBorderColor: '#7e9132',
        },
        Card: { headerFontSize: 16 },
        Modal: { titleFontSize: 19 },
        Table: { headerBg: '#f0f1e9', headerColor: '#555d4a' },
      },
    }}>
      <PlatformMotion><AntApp>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter><MotionLayer/><App /></BrowserRouter>
        </QueryClientProvider>
      </AntApp></PlatformMotion>
    </ConfigProvider>
  </React.StrictMode>,
)

