public class sort {
    public static int prat(int[] arr,int left,int right){
        int index=arr[left];
        while(left<right){
            while(left<right && arr[right]>index){
                right--;
            }
            arr[left]=arr[right];
            while(left<right && arr[left]<index){
                left++;
            }
            arr[right]=arr[left];
        }
        arr[left] = index;
        return left;
    }
    public static void quicksort(int[] arr,int start,int end){
        if (start >= end) return;

        int pivotIndex = prat(arr, start, end);

        quicksort(arr, start, pivotIndex - 1);
        quicksort(arr, pivotIndex + 1, end);
    }

    public static void main(String[] args) {
        int[] arr={3,1,4,9,6,5,8};
        quicksort(arr, 0, arr.length - 1);

        for (int n : arr) {
            System.out.print(n + " ");
        }
    }
}
