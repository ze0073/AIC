import {Button,Flex,Typography} from 'antd'

type Suggestion={label:string;value:string}

export function SuggestedValues({items,onSelect,hint}:{items:Suggestion[];onSelect:(value:string)=>void;hint?:string}){
  return <div style={{margin:'-10px 0 18px'}}>
    <Flex gap={6} align="center" wrap>
      <Typography.Text type="secondary">系统推荐：</Typography.Text>
      {items.map(item=><Button key={item.label} htmlType="button" size="small" onClick={()=>onSelect(item.value)}>{item.label}</Button>)}
    </Flex>
    {hint&&<Typography.Text type="secondary" style={{fontSize:12}}>{hint}</Typography.Text>}
  </div>
}
